package dev.ikm.ds.rocks.maps;

import dev.ikm.ds.rocks.KeyUtil;
import dev.ikm.ds.rocks.EntityKey;
import dev.ikm.ds.rocks.NidCodec6;
import dev.ikm.ds.rocks.spliterator.LongSpliteratorOfPattern;
import dev.ikm.ds.rocks.spliterator.SpliteratorForEntityKeys;
import dev.ikm.ds.rocks.spliterator.SpliteratorForLongKeyOfPattern;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.terms.EntityBinding;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static dev.ikm.ds.rocks.NidCodec6.MAX_PATTERN_SEQUENCE;

public class SequenceMap extends RocksDbMap<RocksDB> {
    private static final Logger LOG = LoggerFactory.getLogger(SequenceMap.class);

    public static final int FIRST_ELEMENT_SEQUENCE_OF_PATTERN = 1;
    private static int nextPatternElementSequence = FIRST_ELEMENT_SEQUENCE_OF_PATTERN;

    public static final UUID PATTERN_PATTERN_UUID = EntityBinding.Pattern.pattern().asUuidArray()[0];
    private static final int patternPatternElementSequence = nextPatternElementSequence++;

    /**
     * Pattern sequence for all patterns.
     * - In two’s complement, -1 is all 1s. As a 16-bit value: 0xFFFF.
     * - The maximum 16-bit unsigned integer is also 0xFFFF, which equals 65535.
     */
    public static final int PATTERN_PATTERN_SEQUENCE = MAX_PATTERN_SEQUENCE;

    public static final EntityKey PATTERN_PATTERN_ENTITY_KEY = EntityKey.of(PATTERN_PATTERN_SEQUENCE, patternPatternElementSequence);
    public static EntityKey patternPatternEntityKey() {
        return PATTERN_PATTERN_ENTITY_KEY;
    }
    /**
     * TODO: Temporary fixed UUID until concepts provide their own pattern PublicId field (currently only semantics do).
     */
    public static final UUID conceptPatternUUID = UUID.fromString("8e9a8888-9d06-45c8-af47-aacc78ed66ee");
    private static final int conceptPatternElementSequence = nextPatternElementSequence++;

    public static EntityKey conceptPatternEntityKey() {
        return EntityKey.of(PATTERN_PATTERN_SEQUENCE, conceptPatternElementSequence);
    }
    /**
     * TODO: Temporary fixed UUID until concepts provide their own pattern PublicId field (currently only semantics do).
     */
    public static final UUID stampPatternUUID = UUID.fromString("15687f5d-6028-4491-b005-7bb6f9f6ebad");
    private static final int stampPatternElementSequence = nextPatternElementSequence++;

    public static EntityKey stampPatternEntityKey() {
        return EntityKey.of(PATTERN_PATTERN_SEQUENCE, stampPatternElementSequence);
    }

    /**
     * Map of pattern sequences to atomic counters used to generate a unique, ordered, sequence for each new element.
     */
    final ConcurrentHashMap<Integer, AtomicLong> nextSequenceMap = new ConcurrentHashMap<>();

    public SequenceMap(RocksDB db, ColumnFamilyHandle mapHandle) {
        super(db, mapHandle);
        open();
    }

    public String sequenceReport() {
        // {1=16, 2=519122, 3=743, 4=400755, 5=371292, 6=371292, 7=1670516, 8=1037842, 9=320, 10=399114, 11=418, 12=969, 13=2, 14=4, 15=2, 63=16}
        StringBuilder sequenceReport = new StringBuilder();

        sequenceReport.append("Sequences\n").append(nextSequenceMap).append("\n");

        for (Map.Entry<Integer, AtomicLong> entry : nextSequenceMap.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            EntityKey patternKey = EntityKey.of(PATTERN_PATTERN_SEQUENCE, entry.getKey());
            int patternNid = NidCodec6.encode(patternKey.patternSequence(), patternKey.elementSequence());
            String patternName = PrimitiveData.textWithNid(patternNid);
            sequenceReport.append(String.format("%,d=%,d, ", entry.getKey(), entry.getValue().get()));
            sequenceReport.append(String.format(
                    "%s | EntityKey: %,d (0x%016X) | EntityNid: %,d (0x%08X)%n\n",
                    patternName,
                    patternKey.longKey(),          // decimal
                    patternKey.longKey(),          // hex (zero-padded to 16 for a long)
                    patternNid,                    // decimal
                    patternNid                     // hex (zero-padded to 8 for an int)
            ));
        }

        return sequenceReport.toString();
    }

    /**
     * Open the nextSequenceMap from RocksDB.
     */
    public void open() {
        try (RocksIterator it = rocksIterator()) {
            it.seekToFirst();
            if (it.isValid()) {
                // At least one entry: populate from DB.
                for (; it.isValid(); it.next()) {
                    byte[] keyBytes = it.key();
                    byte[] valueBytes = it.value();
                    if (keyBytes.length == 4 && valueBytes.length == 8) {
                        int key = KeyUtil.byteArrayToInt(keyBytes);
                        long value = KeyUtil.byteArrayToLong(valueBytes);
                        nextSequenceMap.put(key, new AtomicLong(value));
                    } else {
                        throw new IllegalStateException("key/value lengths out of bounds: " + keyBytes.length + "/" + valueBytes.length);
                    }
                }
            } else {
                // Column family is empty: do identifier bootstrap initialization.
                nextSequenceMap.put(PATTERN_PATTERN_SEQUENCE, new AtomicLong(nextPatternElementSequence)); // Pattern pattern sequences
            }
        }
    }

    /**
     * Save the nextSequenceMap to RocksDB.
     */
    @Override
    protected void writeMemoryToDb() {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            for (Map.Entry<Integer, AtomicLong> entry : nextSequenceMap.entrySet()) {
                int key = entry.getKey();
                long value = entry.getValue().get();
                batch.put(mapHandle, KeyUtil.intToByteArray(key), KeyUtil.longToByteArray(value));
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void closeMap() {
        save();
    }

    /**
     * Get the next sequence for a given pattern. Represented as a 48-bit unsigned integer inside a long value.
     * @param patternSequence
     * @return next sequence for the given pattern.
     */
    public long nextElementSequence(int patternSequence) {
        return nextSequenceMap.computeIfAbsent(patternSequence, k -> new AtomicLong(FIRST_ELEMENT_SEQUENCE_OF_PATTERN)).getAndIncrement();
    }

    /**
     * Generates the next pattern sequence as a long value. This method ensures that
     * sequences are incrementally generated starting at 1. Sequences are maintained per
     * pattern group using a map with atomic counters to provide thread-safe increments.
     *
     * @return the next pattern sequence value starting from 1.
     */
    public int nextPatternSequence() {
        int newPatternSequence = (int) nextSequenceMap.get(PATTERN_PATTERN_SEQUENCE).getAndIncrement();
        nextSequenceMap.put(newPatternSequence, new AtomicLong(FIRST_ELEMENT_SEQUENCE_OF_PATTERN));
        return newPatternSequence;
    }

    public SpliteratorForEntityKeys allEntityLongKeySpliterator() {
        Collection<SpliteratorForLongKeyOfPattern> spliterators = nextSequenceMap.entrySet().stream()
                .map(entry -> new SpliteratorForLongKeyOfPattern(entry.getKey(), FIRST_ELEMENT_SEQUENCE_OF_PATTERN,
                        entry.getValue().get()))
                .toList();
        return new SpliteratorForEntityKeys(spliterators);
    }

    public ImmutableList<SpliteratorForLongKeyOfPattern> allPatternSpliterators() {
        return Lists.immutable.ofAll(
                nextSequenceMap.entrySet().stream()
                .map(entry -> new SpliteratorForLongKeyOfPattern(entry.getKey(), FIRST_ELEMENT_SEQUENCE_OF_PATTERN,
                        entry.getValue().get())).toList());
    }

    public LongSpliteratorOfPattern spliteratorOfPattern(int patternSequence) {
        return nextSequenceMap.entrySet().stream().filter(entry -> entry.getKey() == patternSequence)
                .map(entry -> new SpliteratorForLongKeyOfPattern(patternSequence, FIRST_ELEMENT_SEQUENCE_OF_PATTERN, entry.getValue().get()))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Pattern sequence not found: " + patternSequence));
    }

    public LongSpliteratorOfPattern spliteratorOfPatterns() {
        long maxPatternSequenceExclusive = nextSequenceMap.get(PATTERN_PATTERN_SEQUENCE).get();
        return new SpliteratorForLongKeyOfPattern(PATTERN_PATTERN_SEQUENCE,  FIRST_ELEMENT_SEQUENCE_OF_PATTERN, maxPatternSequenceExclusive);
    }
}
