package dev.ikm.ds.rocks.maps;


import dev.ikm.ds.rocks.KeyUtil;
import dev.ikm.ds.rocks.EntityKey;
import dev.ikm.ds.rocks.tasks.ImportProtobufTask;
import dev.ikm.tinkar.common.id.PublicId;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.ikm.ds.rocks.maps.SequenceMap.PATTERN_PATTERN_SEQUENCE;
import static dev.ikm.ds.rocks.maps.SequenceMap.patternPatternEntityKey;

public class UuidEntityKeyMap
        extends RocksDbMap<RocksDB> {
    private static final Logger LOG = LoggerFactory.getLogger(UuidEntityKeyMap.class);
    enum Mode {
        /**
         * UUIDs are not yet stored in the {@code uuidEntityKeyMap} and the db has not been checked for values.
         */
        NOT_STARTED,
        /**
         * All UUIDs are stored in the {@code uuidEntityKeyMap}, no need to check DB.
         */
        ALL_IN_MEMORY,
        /**
         * UUIDs are lazily retrieved from the DB and stored in the {@code uuidEntityKeyMap}.
         * UUIDs will remain in the {@code uuidEntityKeyMap} until removed, so new UUIDs will in memory, and
         * will need to be written to the DB before closing the db.
         */
        CACHING
    }

    private static final ScopedValue<PublicId> ENTITY_PUBLIC_ID = ScopedValue.newInstance();
    private static final ScopedValue<EntityKey> PATTERN_ENTITY_KEY = ScopedValue.newInstance();

    MultiUuidLockTable uuidLockTable = new MultiUuidLockTable();

    final boolean loadOnStart = false;

    final AtomicReference<Mode> memoryMode = new AtomicReference<>(Mode.NOT_STARTED);

    final SequenceMap sequenceMap;

    final ConcurrentHashMap<UUID, EntityKey> uuidEntityKeyMap = new ConcurrentHashMap<>();

    public UuidEntityKeyMap(RocksDB db,
                            ColumnFamilyHandle mapHandle,
                            SequenceMap sequenceMap) {
        super(db, mapHandle);
        this.sequenceMap = sequenceMap;
        this.open();
    }

    // TODO: Temp, only checks for existence of key in memory.
    public ImmutableList<UUID> getUuids(long longKey) {
        MutableList<UUID> matchingUuids = Lists.mutable.empty();
        for (Map.Entry<UUID, EntityKey> entry : uuidEntityKeyMap.entrySet()) {
            if (entry.getValue().longKey() == longKey) {
                matchingUuids.add(entry.getKey());
            }
        }
        return matchingUuids.toImmutable();
    }

    /**
     * Flushes all values of uuidEntityKeyMap to the RocksDB column.
     * Sorts the UUID keys using Arrays.parallelSort and writes values in parallel using virtual threads and structured concurrency.
     */
    @Override
    public void writeMemoryToDb() {
        // Snapshot keys to avoid concurrent modification while writing/removing
        java.util.ArrayList<UUID> uuids = new java.util.ArrayList<>(uuidEntityKeyMap.keySet());

        // Larger chunks for fewer db.write calls; processed sequentially to prevent stalls
        final int chunkSize = 16_384;

        try (WriteOptions writeOptions = new WriteOptions()
                .setDisableWAL(true) // bulk flush; acceptable during import/close
                .setSync(false)) {
            this.memoryMode.set(Mode.CACHING);

            // Process chunks sequentially to reduce backpressure
            for (int i = 0; i < uuids.size(); i += chunkSize) {
                final int start = i;
                final int end = Math.min(i + chunkSize, uuids.size());
                try (WriteBatch batch = new WriteBatch()) {
                    for (int j = start; j < end; ++j) {
                        UUID uuid = uuids.get(j);
                        EntityKey entityKey = uuidEntityKeyMap.get(uuid);
                        if (entityKey == null) {
                            continue;
                        }
                        byte[] keyBytes = KeyUtil.uuidToByteArray(uuid);
                        byte[] valueBytes = entityKey.toBytes();
                        batch.put(mapHandle, keyBytes, valueBytes);
                    }

                    // Retry with exponential backoff on backpressure
                    int attempts = 0;
                    long backoffMillis = 2;
                    while (true) {
                        try {
                            db.write(writeOptions, batch);
                            break;
                        } catch (RocksDBException e) {
                            org.rocksdb.Status st = e.getStatus();
                            boolean retryable = st != null &&
                                    (st.getCode() == org.rocksdb.Status.Code.Busy ||
                                     st.getCode() == org.rocksdb.Status.Code.Incomplete);
                            if (retryable && attempts++ < 10) {
                                // Proactively flush to relieve pressure
                                try (org.rocksdb.FlushOptions fo = new org.rocksdb.FlushOptions().setWaitForFlush(true)) {
                                    db.flush(fo);
                                } catch (RocksDBException ignore) {
                                    // best-effort flush
                                }
                                try {
                                    Thread.sleep(backoffMillis);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                backoffMillis = Math.min(500, backoffMillis * 2);
                                continue;
                            }
                            throw e;
                        }
                    }

                    // Remove flushed keys from memory
                    for (int j = start; j < end; ++j) {
                        uuidEntityKeyMap.remove(uuids.get(j));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to flush uuidEntityKeyMap to RocksDB", e);
        }
    }

    /**
     * Opens the UUID-EntityKey-Nid map from RocksDB.
     * If the column family is empty, performs special initialization actions.
     */
    public void open() {
        try (RocksIterator it = rocksIterator()) {
            it.seekToFirst();
            if (it.isValid()) {
                if (loadOnStart) {
                    // Load any persisted UUID -> EntityKey mappings into memory.
                    loadAllUuids();
                    memoryMode.set(Mode.ALL_IN_MEMORY);
                } else {
                    memoryMode.set(Mode.CACHING);
                }
            } else {
                // The column family is empty, subsequent imports will put all UUID/EntityKey pairs into the uuidEntityKeyMap.
                memoryMode.set(Mode.ALL_IN_MEMORY);
                // Bootstrap with pattern UUIDs for Pattern, Concept, and Stamp entities.
                uuidEntityKeyMap.put(SequenceMap.PATTERN_PATTERN_UUID, patternPatternEntityKey());
                uuidEntityKeyMap.put(SequenceMap.conceptPatternUUID, SequenceMap.conceptPatternEntityKey());
                uuidEntityKeyMap.put(SequenceMap.stampPatternUUID, SequenceMap.stampPatternEntityKey());
            }
        }
    }

    private void loadAllUuids() {
        try (RocksIterator it = rocksIterator()) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] keyBytes = it.key();
                byte[] valueBytes = it.value();
                if (keyBytes.length == 16 && valueBytes.length >= 12) {
                    // UUID: 16 bytes, EntityKey: at least 12 bytes if 3 ints
                    UUID uuid = KeyUtil.byteArrayToUuid(keyBytes);
                    EntityKey entityKey = KeyUtil.entityKeyToBytes(valueBytes);
                    uuidEntityKeyMap.put(uuid, entityKey);
                }
            }
        }
    }

    @Override
    protected void closeMap() {
        writeMemoryToDb();
    }

    public EntityKey getEntityKey(PublicId patternId, PublicId entityId) {
        if (ImportProtobufTask.SCOPED_WATCH_LIST.isBound()) {
            Set<UUID> watchList = ImportProtobufTask.SCOPED_WATCH_LIST.get();
            Set<UUID> values = patternId.asUuidList().toSet();
            values.addAll(entityId.asUuidList().toSet());

            if (watchList.stream().anyMatch(values::contains)) {
                LOG.info("Watch in public id found: patternId {} and entityId {} found", patternId, entityId);
            }
        }

        EntityKey patternKey = ScopedValue.where(ENTITY_PUBLIC_ID, patternId).call(() ->
                switch (patternId.uuidCount()) {
                    case 1 -> uuidEntityKeyMap.computeIfAbsent(patternId.asUuidArray()[0], this::makePatternEntityKey);
                    default -> uuidEntityKeyMap.computeIfAbsent(patternId.asUuidArray()[0], this::makeMultiUuidPatternEntityKey);
                });

        return ScopedValue.where(PATTERN_ENTITY_KEY, patternKey)
                .where(ENTITY_PUBLIC_ID, entityId).call(() ->
              switch (entityId.uuidCount()) {
                case 1 -> uuidEntityKeyMap.computeIfAbsent(entityId.asUuidArray()[0], this::makeEntityKey);
                default -> uuidEntityKeyMap.computeIfAbsent(entityId.asUuidArray()[0], this::makeMultiUuidEntityKey);
            });
    }

    private EntityKey makeMultiUuidPatternEntityKey(UUID uuid) {
        return getEntityKey(uuid).orElseGet(() -> makeMultiUuidEntityKey(this::makePatternEntityKey));
    }

    private EntityKey makeMultiUuidEntityKey(UUID uuid) {
        return getEntityKey(uuid).orElseGet(() -> makeMultiUuidEntityKey(this::makeEntityKey));
    }

    private EntityKey makeEntityKey(UUID uuid) {
        return getEntityKey(uuid).orElseGet(() -> {
            EntityKey patternKey = PATTERN_ENTITY_KEY.get();
            // If the enclosing pattern is the "Pattern" pattern, this is a Pattern entity.
            // Ensure it lives under PATTERN_PATTERN_SEQUENCE, not under the pattern's own sequence (e.g., 1 -> 0x04...).
            if (patternKey.equals(SequenceMap.patternPatternEntityKey())) {
                int patternSequence = PATTERN_PATTERN_SEQUENCE;
                long patternElementSequence = this.sequenceMap.nextPatternSequence();
                return EntityKey.of(patternSequence, patternElementSequence);
            }
            // Regular entities: use the pattern's own sequence bucket (elementSequence allocated within that bucket).
            int patternSequence = (int) patternKey.elementSequence();
            long patternElementSequence = this.sequenceMap.nextElementSequence(patternSequence);
            return EntityKey.of(patternSequence, patternElementSequence);
        });
    }

    private EntityKey makePatternEntityKey(UUID uuid) {
        return getEntityKey(uuid).orElseGet(() -> {
            int patternSequence = this.sequenceMap.nextPatternSequence();
            if (patternSequence == 1) {
                LOG.warn("Pattern sequence 1 is reserved for the pattern entity key");
            }
            EntityKey patternEntityKey = EntityKey.of(PATTERN_PATTERN_SEQUENCE, patternSequence);
            return patternEntityKey;
        });
    }

    private EntityKey makeMultiUuidEntityKey(Function<UUID, EntityKey> creator) {
        PublicId entityPublicId = ENTITY_PUBLIC_ID.get();
        uuidLockTable.lock(entityPublicId);
        EntityKey entityKey = null;
        try {
            // See if an EntityKey is already in the uuidEntityKeyMap.
            for (UUID entityUuid : entityPublicId.asUuidArray()) {
                if (uuidEntityKeyMap.containsKey(entityUuid)) {
                    entityKey = uuidEntityKeyMap.get(entityUuid);
                    break;
                }
            }
            if (entityKey != null) {
                // Ensure entityKey is associated with all UUIDs.
                for (UUID entityUuid : entityPublicId.asUuidArray()) {
                    if (!uuidEntityKeyMap.containsKey(entityUuid)) {
                        uuidEntityKeyMap.put(entityUuid, entityKey);
                    }
                }
            } else {
                UUID[] uuids = entityPublicId.asUuidArray();
                entityKey = creator.apply(uuids[0]);
                for (int i = 1; i < uuids.length; i++) {
                    addUuidToEntityKeyMap(uuids[i], entityKey);
                }
            }
        } finally {
            uuidLockTable.unlock(entityPublicId);
        }
        return entityKey;
    }

    private void addUuidToEntityKeyMap(UUID uuid, EntityKey entityKey) {
        uuidEntityKeyMap.put(uuid, entityKey);
        if (memoryMode.get() == Mode.CACHING) {
            byte[] keyBytes = KeyUtil.uuidToByteArray(uuid);
            if (!keyExists(keyBytes)) {
                put(keyBytes, entityKey.toBytes());
            }
        }
    }

    public Optional<EntityKey> getEntityKey(UUID uuid) {
        if (uuidEntityKeyMap.containsKey(uuid)) {
            return Optional.of(uuidEntityKeyMap.get(uuid));
        }
        if (memoryMode.get() == Mode.CACHING) {
            byte[] keyBytes = KeyUtil.uuidToByteArray(uuid);
            if (keyExists(keyBytes)) {
                return Optional.of(KeyUtil.entityKeyToBytes(get(keyBytes)));
            }
        }
        return Optional.empty();
    }
}
