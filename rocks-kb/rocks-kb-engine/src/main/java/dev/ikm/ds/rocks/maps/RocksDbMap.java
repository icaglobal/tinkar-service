package dev.ikm.ds.rocks.maps;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class RocksDbMap<DB extends RocksDB> {
    private static final Logger LOG = LoggerFactory.getLogger(RocksDbMap.class);
    protected final DB db;
    protected final ColumnFamilyHandle mapHandle;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RocksDbMap(DB db, ColumnFamilyHandle mapHandle) {
        this.db = db;
        this.mapHandle = mapHandle;
    }

    protected RocksIterator rocksIterator() {

        return db.newIterator(mapHandle);
    }

    protected RocksIterator rocksIterator(ReadOptions ro) {
        return db.newIterator(mapHandle, ro);
    }

    public final void put(byte[] key, byte[] value) {
        try {
            db.put(this.mapHandle, key, value);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public final byte[] get(byte[] key) {
        try {
            return db.get(mapHandle, key);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public final void delete(byte[] key) throws RocksDBException {
        db.delete(mapHandle, key);
    }

    public final boolean keyExists(byte[] key) {
        return db.keyExists(mapHandle, key);
    }

    public final void save() {
        writeMemoryToDb();
        try (FlushOptions flushOptions = new FlushOptions()) {
            db.flush(flushOptions);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    protected abstract void writeMemoryToDb();

    public final void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // already closed
        }
        // Allow subclasses to finish their work and stop threads before closing the handle
        closeMap();
        save();
        try {
            if (mapHandle != null) {
                mapHandle.close();
            }
        } catch (Exception ignore) {
            LOG.warn("Error closing DB column family", ignore);
        }

    }

    protected abstract void closeMap();
}
