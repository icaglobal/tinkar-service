package dev.ikm.ds.rocks.maps;

import dev.ikm.ds.rocks.KeyUtil;
import dev.ikm.ds.rocks.EntityKey;
import dev.ikm.ds.rocks.spliterator.LongSpliteratorOfPattern;
import dev.ikm.ds.rocks.internal.Get;
import dev.ikm.tinkar.common.service.PrimitiveDataService;
import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.ByteLists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.ImmutableByteList;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import java.util.function.ObjIntConsumer;

import static dev.ikm.tinkar.entity.EntityRecordFactory.ENTITY_FORMAT_VERSION;

public class EntityMap
        extends RocksDbMap<RocksDB> {

    private static final Logger LOG = LoggerFactory.getLogger(EntityMap.class);

    private final UuidEntityKeyMap uuidEntityKeyMap;

    private final AtomicLong writeSequence = new AtomicLong(0);
    final AtomicLong lastFlushedSequence = new AtomicLong();
    final ReentrantLock flushLock = new ReentrantLock();
    final Condition flushed = flushLock.newCondition();
    // running flag to control writer lifecycle
    private final AtomicBoolean running = new AtomicBoolean(true);

    private record WriteRecord(long writeSequence, long key, ImmutableList<ImmutableByteList> entityParts) {}

    private final ConcurrentHashMap<Long, WriteRecord> pendingWritesMap = new ConcurrentHashMap<>();

    private final LinkedBlockingDeque<WriteRecord> pendingWrites = new LinkedBlockingDeque<>();

    private final Thread writeThread = new Thread(() -> {
        // Drain remaining writes even after running=false
        while (running.get() || !pendingWritesMap.isEmpty()) {
            WriteRecord firstRecord = null;
            try {
                // BLOCKING WAIT: Wait for work to arrive.
                // Using poll with timeout allows checking the 'running' flag periodically.
                firstRecord = pendingWrites.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (firstRecord == null) {
                continue;
            }

            try (WriteBatch batch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions()
                         .setDisableWAL(true)
                         .setSync(false)
                         .setNoSlowdown(true)) {

                // Initialize list with the item we already retrieved
                MutableList<WriteRecord> writeRecords = Lists.mutable.with(firstRecord);
                long maxSeqInThisBatch = firstRecord.writeSequence;

                // Add the first record immediately
                addToBatch(batch, firstRecord);

                int batchCount = 1;
                boolean interrupted = false;

                // Batch up additional records if immediately available
                // We don't need to wait (poll with timeout) here; we want to batch what's ready
                while (batchCount++ < 16384 && !interrupted) {
                    try {
                        WriteRecord writeRecord = pendingWrites.poll(); // Non-blocking poll for subsequent items
                        if (writeRecord == null) {
                            break;
                        }
                        maxSeqInThisBatch = Math.max(maxSeqInThisBatch, writeRecord.writeSequence);
                        addToBatch(batch, writeRecord);
                        writeRecords.add(writeRecord);
                    } catch (RocksDBException e) {
                        throw new RuntimeException(e);
                    }
                }

                if (!writeRecords.isEmpty()) {
                    long backoffMillis = 1;
                    final int maxAttempts = 8;
                    
                    // Refactored: Explicit loop instead of while(true)
                    for (int attempt = 0; attempt <= maxAttempts; attempt++) {
                        try {
                            db.write(writeOptions, batch);
                            break; // Success, exit loop
                        } catch (RocksDBException e) {
                            org.rocksdb.Status st = e.getStatus();
                            boolean retryable = st != null &&
                                    (st.getCode() == org.rocksdb.Status.Code.Busy ||
                                     st.getCode() == org.rocksdb.Status.Code.Incomplete);

                            // If this was the last attempt, or error is not retryable, fail hard
                            if (!retryable || attempt == maxAttempts) {
                                throw e;
                            }

                            try {
                                Thread.sleep(backoffMillis);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                // If interrupted during backoff, abort operations
                                throw new RuntimeException("Write thread interrupted during retry", ie);
                            }
                            backoffMillis = Math.min(200, backoffMillis * 2);
                        }
                    }

                    lastFlushedSequence.set(maxSeqInThisBatch);
                    flushLock.lock();
                    try {
                        flushed.signalAll();
                    } finally {
                        flushLock.unlock();
                    }

                    for (WriteRecord writeRecord : writeRecords) {
                        pendingWritesMap.remove(writeRecord.key, writeRecord);
                    }
                }
            } catch (RocksDBException e) {
                LOG.error("Failed to write batch to RocksDB", e);
                throw new RuntimeException(e);
            }
        }
    }, "EntityMap-Writer");

    // Helper method to reduce code duplication
    private void addToBatch(WriteBatch batch, WriteRecord writeRecord) throws RocksDBException {
        boolean isStamp = isStamp(writeRecord.entityParts.get(0));
        for (int i = 0; i < writeRecord.entityParts.size(); i++) {
            byte[] key = makeKey(writeRecord.key, isStamp, i, writeRecord.entityParts);
            byte[] partBytes = writeRecord.entityParts.get(i).toArray();
            if (partBytes == null || partBytes.length == 0) {
                throw new IllegalStateException("writeThread: entityParts is empty");
            }
            batch.put(mapHandle, key, partBytes);
        }
    }

    public EntityMap(RocksDB db, ColumnFamilyHandle mapHandle, UuidEntityKeyMap uuidEntityKeyMap) {
        super(db, mapHandle);
        this.uuidEntityKeyMap = uuidEntityKeyMap;
        writeThread.setDaemon(true);
        writeThread.start();
    }

    // Gracefully stop the writer and flush pending data (call this before closing DB)
    public final void closeMap() {
        // First, ensure all pending writes are flushed while the writer is still running
        writeMemoryToDb();
        // Then signal shutdown and wait for the writer to drain any small tail
        running.set(false);
        try {
            writeThread.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void writeMemoryToDb() {
        // Wait until all queued writes have been flushed (bounded wait)
        long target = writeSequence.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120); // 2m bound; adjust as needed
        flushLock.lock();
        try {
            while (lastFlushedSequence.get() < target) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    LOG.warn("Timed out waiting for flush. lastFlushed={} target={}",
                            lastFlushedSequence.get(), target);
                    break;
                }
                try {
                    flushed.awaitNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(200), remainingNanos));
                } catch (InterruptedException e) {
                    // preserve interrupt, keep waiting within bound
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            flushLock.unlock();
        }
    }

    public byte[] get(EntityKey key) {
        return get(key.longKey());
    }

    private MutableList<ImmutableByteList> getEntityParts(long longKey) {
        if (keyExists(KeyUtil.longToByteArray(longKey))) {
            try (ReadOptions ro = new ReadOptions()
                    .setPrefixSameAsStart(true)     // optional: keep iteration within prefix
                    .setTotalOrderSeek(true);     // optional: ignore prefix bloom; full-order seek
                 RocksIterator iterator = rocksIterator(ro)) {
                return getEntityParts(longKey, iterator);
            }
        }
        return Lists.mutable.empty();
    }

    private MutableList<ImmutableByteList> getEntityParts(long longKey, RocksIterator iterator) {

        byte[] entityPrefix = KeyUtil.longToByteArray(longKey);
        iterator.seek(entityPrefix);

        return getEntityParts(iterator, entityPrefix);
    }

    /**
     * Gets the entity parts from the iterator starting at the given entity prefix.
     * After this method completes, the iterator will be positioned after the last part
     * belonging to the entity with the given prefix.
     *
     * @param iterator     the RocksDB iterator to read from
     * @param entityPrefix the prefix bytes that identify the entity
     * @return a list of the entity parts, or null if the entity is not found
     */
    private MutableList<ImmutableByteList> getEntityParts(RocksIterator iterator, byte[] entityPrefix) {
        MutableList<ImmutableByteList> results = Lists.mutable.empty();
        // Process the chronology part distinctly
        if (iterator.isValid() && startsWith(iterator.key(), entityPrefix)) {
            results.add(ByteLists.immutable.of(iterator.value()));
        } else {
            //LOG.warn("Entity byte[] not found: " + String.format("0x%016X", longKey) + " uuids: " + uuids);
            return null;
        }

        // Process the version part(s) distinctly
        for (iterator.next();
             iterator.isValid() && startsWith(iterator.key(), entityPrefix);
             iterator.next()) {
            results.add(ByteLists.immutable.of(iterator.value()));
        }
        return results;
    }

    public byte[] get(long longKey) {
        WriteRecord pendingWrite = pendingWritesMap.get(longKey);
        if (pendingWrite != null) {
            return mergeParts(pendingWrite.entityParts);
        }
        if (keyExists(KeyUtil.longToByteArray(longKey)) == false) {
            return null;
        }
        return mergeParts(getEntityParts(longKey).toImmutable());
    }

    private static byte[] mergeParts(ImmutableList<ImmutableByteList> entityParts) {
        int size = entityParts.stream().mapToInt(b -> b.size()).sum() + entityParts.size() * 16;
        if (size == 0) {
            LOG.error("mergeParts: entityParts is empty");
            return null;
        }
        ByteBuf buf = ByteBufPool.allocate(size);
        buf.writeInt(entityParts.size());
        for (int i = 0; i < entityParts.size(); i++) {
            if (i == 0) {
                buf.writeInt(entityParts.get(i).size() + 4 + 1); // number of bytes in the first array + 4 byte size + 1 byte format version.
                buf.writeByte(ENTITY_FORMAT_VERSION);
                buf.put(entityParts.get(i).toArray());
                buf.writeInt(entityParts.size() -1);
            } else {
                buf.writeInt(entityParts.get(i).size()); // Number of bytes in the version part
                buf.put(entityParts.get(i).toArray());
            }
        }
        byte[] results = new byte[buf.readRemaining()];
        buf.read(results, 0, results.length);
        return results;
    }

    private boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }


    public void put(EntityKey entityKey, byte[] value) {
        if (entityKey instanceof EntityKey.EntityVersionKey) {
            throw new IllegalArgumentException("EntityVersionKey should not be used for put, only the EntityKey.");
        }
        put(entityKey.longKey(), value);
    }

    public void put(long longKey, byte[] value) {
        // a possibly empty existing part list. 
        ImmutableList<ImmutableByteList> newParts = extractVersionParts(value).toImmutable();
        WriteRecord writeRecord = new WriteRecord(writeSequence.incrementAndGet(), longKey, newParts);

        pendingWritesMap.merge(longKey, writeRecord, (oldRecord, newRecord) -> {
            if (!newRecord.entityParts.get(0).equals(oldRecord.entityParts.get(0))) {
                throw new IllegalStateException("Entity parts[0] must be the same for the same longKey.");
            }
            MutableList<ImmutableByteList> mergedParts = oldRecord.entityParts.toList();
            for (int i = 1; i < newRecord.entityParts.size(); i++) {
                if (!mergedParts.contains(newRecord.entityParts.get(i))) {
                    mergedParts.add(newRecord.entityParts.get(i));
                }
            }
            boolean changed = mergedParts.size() != oldRecord.entityParts.size();
            if (changed) {
                WriteRecord mergedWriteRecord = new WriteRecord(writeSequence.incrementAndGet(), longKey, mergedParts.toImmutable());
                pendingWrites.add(mergedWriteRecord);
                return mergedWriteRecord;
            }
            return oldRecord;
        });
        pendingWrites.add(writeRecord);
    }

    private static byte[] makeKey(long longKey, boolean isStamp, int partIndex, ImmutableList<ImmutableByteList> chronologyParts) {
        if (partIndex == 0) {
            return KeyUtil.longToByteArray(longKey);
        }
        if (isStamp) {
            return KeyUtil.stampVersionKey(longKey, Get.stampSequenceForStampNid(getStampNid(chronologyParts.get(partIndex))), (byte) partIndex);
        }
        return KeyUtil.elementVersionKey(longKey, Get.stampSequenceForStampNid(getStampNid(chronologyParts.get(partIndex))));
    }

    /**
     * See dev.ikm.tinkar.entity.EntityRecordFactory.getBytes() for the format of the byte array.
     *
     * @param bytes
     * @throws IOException
     */
    private static MutableList<ImmutableByteList> extractVersionParts(byte[] bytes) {
        ByteBuf readBuf = ByteBuf.wrapForReading(bytes);
        final int partCount = readBuf.readInt();
        MutableList<ImmutableByteList> result = Lists.mutable.ofInitialCapacity(partCount);
        for (int i = 0; i < partCount; i++) {
            int partSize = readBuf.readInt();
            if (i == 0) {
                byte localEntityFormat = readBuf.readByte();
                if (localEntityFormat != ENTITY_FORMAT_VERSION) {
                    throw new IllegalStateException("All entities should be the same format. Found: " + ENTITY_FORMAT_VERSION + " != " + localEntityFormat);
                }
                // The first array is the chronicle and has a field for the number of versions...
                // Add one for the entityFormat token.
                byte[] tmpPart = new byte[partSize - 5];
                readBuf.read(tmpPart);
                result.add(ByteLists.immutable.of(tmpPart));
                int versionCount = readBuf.readInt();
                if (versionCount != partCount - 1) {
                    throw new IllegalStateException("Malformed data. versionCount: " +
                            versionCount + " arrayCount: " + partCount);
                }
                // Version count is not included as the version count may change as a result of merge.
                // It must be added back in after sorting unique versions.
            } else {
                byte[] tmpPart = new byte[partSize];
                readBuf.read(tmpPart);
                result.add(ByteLists.immutable.of(tmpPart));
            }
        }
        return result;
    }
    private static int getStampNid(ImmutableByteList result) {
        int stampNid = ((result.get(1) & 0xFF) << 24) |
                ((result.get(2) & 0xFF) << 16) |
                ((result.get(3) & 0xFF) << 8) |
                ((result.get(4) & 0xFF) << 0);
        return stampNid;
    }

    private static boolean isStamp(ImmutableByteList chronologyPart) {
        return chronologyPart.get(0) == PrimitiveDataService.STAMP_DATA_TYPE;
    }

    public void forEach(ObjIntConsumer<byte[]> entityHandler) {
        final int allowedErrors = 5;
        int errors = 0;
        int count = 0;
        try (final Snapshot s = db.getSnapshot();
             final ReadOptions ro =
                     new ReadOptions().setPrefixSameAsStart(false) // 2 byte Column Family prefix, and 8 byte key prefix
                             .setTotalOrderSeek(false)
                             .setSnapshot(s);
             RocksIterator it = rocksIterator(ro)) {
            for (it.seekToFirst(); it.isValid(); ) {
                if (it.isValid()) {
                    MutableList<ImmutableByteList> partList = Lists.mutable.ofInitialCapacity(10);
                    byte[] entityPrefix = it.key();
                    partList.add(ByteLists.immutable.of(it.value()));
                    it.next(); // may invalidate; rechecked at loop head
                    // Drain all entries that share this entity prefix
                    while (it.isValid() && startsWith(it.key(), entityPrefix)) {
                        partList.add(ByteLists.immutable.of(it.value()));
                        it.next(); // may invalidate; rechecked at loop head
                    }
                    ImmutableList<ImmutableByteList> parts = partList.toImmutable();
                    int nid = extractNid(parts);
                    entityHandler.accept(mergeParts(parts), nid);
                }
            }
        }
    }


    public void scanEntitiesInRange(LongSpliteratorOfPattern spliterator,
                                    ObjIntConsumer<byte[]> entityHandler) {
        Objects.requireNonNull(entityHandler, "entityHandler");

        try (final Snapshot s = db.getSnapshot();
             final ReadOptions ro =
                     new ReadOptions().setPrefixSameAsStart(false) // 2 byte Column Family prefix, and 8 byte key prefix
                                      .setTotalOrderSeek(false)
                                      .setSnapshot(s);
             final RocksIterator it = rocksIterator(ro)) {
            byte[] firstPrefix = KeyUtil.longToByteArray(spliterator.peek());
            it.seek(firstPrefix);

            if (it.isValid()) {
                while (spliterator.tryAdvance((LongConsumer) longKey -> {
                    byte[] entityPrefix = KeyUtil.longToByteArray(longKey);
                    // Ensure we are positioned at or after this entity
                    if (!it.isValid() || !startsWith(it.key(), entityPrefix)) {
                        it.seek(entityPrefix);
                    }

                    // If entity exists, consume base and versions
                    if (it.isValid() && startsWith(it.key(), entityPrefix)) {
                        // base
                        MutableList<ImmutableByteList> partList = Lists.mutable.ofInitialCapacity(10);
                        partList.add(ByteLists.immutable.of(it.value()));
                        it.next(); // may invalidate; rechecked at loop head
                        // Drain all entries that share this entity prefix
                        // versions
                        while (it.isValid() && startsWith(it.key(), entityPrefix)) {
                            partList.add(ByteLists.immutable.of(it.value()));
                            it.next(); // may invalidate; rechecked at loop head
                        }
                        ImmutableList<ImmutableByteList> parts = partList.toImmutable();
                        int nid = extractNid(parts);
                        entityHandler.accept(mergeParts(parts), nid);
                    }
                }));
            }
        }
    }

    public static int extractNid(ImmutableList<ImmutableByteList> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("parts must contain at least the chronology part");
        }
        ImmutableByteList chronPart = parts.get(0);
        if (chronPart.size() < 5) {
            throw new IllegalArgumentException("chronology part is too small to contain nid at [2..5]: " + chronPart +
                    "\n\n" + parts);
        }
        return ((chronPart.get(1) & 0xFF) << 24) |
                ((chronPart.get(2) & 0xFF) << 16) |
                ((chronPart.get(3) & 0xFF) << 8)  |
                (chronPart.get(4) & 0xFF);
    }

}
