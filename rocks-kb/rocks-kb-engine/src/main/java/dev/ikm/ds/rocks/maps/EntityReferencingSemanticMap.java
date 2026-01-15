package dev.ikm.ds.rocks.maps;

import dev.ikm.ds.rocks.KeyUtil;
import dev.ikm.ds.rocks.EntityKey;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.rocksdb.*;

public class EntityReferencingSemanticMap
        extends RocksDbMap<RocksDB> {

    private static final byte[] emptyValue = new byte[0];

    public EntityReferencingSemanticMap(RocksDB db, ColumnFamilyHandle mapHandle) {
        super(db, mapHandle);

        try (final ColumnFamilyOptions cfo = new ColumnFamilyOptions()) {
            cfo.useFixedLengthPrefixExtractor(8); // first 8 bytes as prefix

            final BlockBasedTableConfig t = new BlockBasedTableConfig()
                    .setFilterPolicy(new BloomFilter(10, false))
                    .setWholeKeyFiltering(false)
                    .setCacheIndexAndFilterBlocks(true);

            cfo.setTableFormatConfig(t);
            cfo.setMemtablePrefixBloomSizeRatio(0.125);
            // use cfo when creating/opening the column family
        }
    }

    @Override
    protected void writeMemoryToDb() {
        // Nothing buffered, all direct writes to the DB. So no need to write before the flush.
    }

    @Override
    protected void closeMap() {
        // Nothing buffered, all direct writes to the DB. So no need to write before the flush.
    }

    public void add(EntityKey entityKey, EntityKey referencingEntityKey) {
        byte[] compoundKey = KeyUtil.entityReferencingSemanticKey(entityKey, referencingEntityKey);
        put(compoundKey, emptyValue);
    }

    public ImmutableList<EntityKey> getReferencingEntityKeys(EntityKey entityKey) {
        return getReferencingEntityKeys(entityKey.longKey());
    }

    public ImmutableList<EntityKey> getReferencingEntityKeysOfPattern(EntityKey entityKey, EntityKey patternEntityKey) {
        ImmutableList<EntityKey> referencingEntityKeys = getReferencingEntityKeys(entityKey.longKey());
        return referencingEntityKeys.select(referencingEntityKey -> referencingEntityKey.patternSequence() == patternEntityKey.patternSequence());
    }
    public ImmutableList<EntityKey> getReferencingEntityKeysOfPattern(long entityKey, int patternSequence) {
        ImmutableList<EntityKey> referencingEntityKeys = getReferencingEntityKeys(entityKey);
        return referencingEntityKeys.select(referencingEntityKey -> referencingEntityKey.patternSequence() == patternSequence);
    }

    public ImmutableList<EntityKey> getReferencingEntityKeys(long longKey) {
        byte[] prefix = KeyUtil.longToByteArray(longKey);
        MutableList<EntityKey> results = Lists.mutable.empty();

        try (RocksIterator it = rocksIterator()) {
            for (it.seek(prefix); it.isValid() && startsWith(it.key(), prefix); it.next()) {
                EntityKey referencingEntityKey = KeyUtil.referencingEntityKeyFromEntityReferencingSemanticKey(it.key());
                results.add(referencingEntityKey);
            }
        }
        return results.toImmutable();
    }


    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }
}
