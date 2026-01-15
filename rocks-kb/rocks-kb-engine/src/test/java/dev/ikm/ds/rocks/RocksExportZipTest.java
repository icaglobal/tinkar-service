package dev.ikm.ds.rocks;

import dev.ikm.ds.rocks.tasks.ImportProtobufTask;
import dev.ikm.tinkar.common.service.DataUriOption;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Disabled
public class RocksExportZipTest {

    // Point at your input zip used by RocksImportTask
    private final File importFile = new File("/path/to/your/input.pb.zip");

    private static final int NID_SEQUENCE_KEY = -1; // mirror of SequenceMap.NID_SEQUENCE_KEY
    private static final int FIRST_ELEMENT_SEQUENCE_OF_PATTERN = 1; // mirror of SequenceMap.FIRST_ELEMENT_SEQUENCE_OF_PATTERN

    @BeforeEach
    void setUp() {
        File defaultDataDirectory = new File("target/rocksdb/");
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, defaultDataDirectory);
        RocksProvider.NewController controller = new RocksProvider.NewController();
        controller.setDataServiceProperty(RocksProvider.NewController.NEW_FOLDER_PROPERTY, "RocksKb");
        controller.setDataUriOption(new DataUriOption("kb-source", importFile.toURI()));
        PrimitiveData.selectControllerByClass(RocksProvider.NewController.class);
        PrimitiveData.start();
    }

    @AfterEach
    void tearDown() {
        PrimitiveData.get().close();
    }

    @Test
    void exportKnowledgeBaseToZip() throws Exception {
        // 1) Build the RocksKB by running the import task
        HashSet<UUID> watchList = new HashSet<>();
        ImportProtobufTask importTask = new ImportProtobufTask(importFile, RocksProvider.get(), watchList);
        importTask.compute();

        // 2) Compute expectations from the SequenceMap (DEFAULT CF)
        //    Map: patternSequence -> lastElementExclusive
        TreeMap<Integer, Long> patternToLastExclusive = loadSequenceExpectations();

        // 3) Validate EntityMap iteration order matches sequence expectations (by longKey)
        long expectedEntityCount = expectedEntityCount(patternToLastExclusive);
        long validatedEntityCount = validateEntityMapOrderAndCounts(patternToLastExclusive);
        if (validatedEntityCount != expectedEntityCount) {
            throw new AssertionError("Validated entity count " + validatedEntityCount +
                    " != expected " + expectedEntityCount);
        }

        // 5) Export to zip (only after validations pass)
        File outDir = new File("target/export");
        Files.createDirectories(outDir.toPath());
        File outZip = new File(outDir, "rocks-kb-export.zip");

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outZip)))) {
            long seqEntries = writeSequenceMapEntry(zos);     // raw seq map
            long entityMapEntries = writeEntityMapEntry(zos);  // all EntityMap KV pairs

             writeManifest(zos, seqEntries, entityMapEntries,
                    expectedEntityCount, validatedEntityCount, patternToLastExclusive);
            writeValidationSummary(zos, expectedEntityCount, validatedEntityCount);
        }
    }

    // --- Validation helpers ---

    private TreeMap<Integer, Long> loadSequenceExpectations() {
        RocksDB db = RocksProvider.get().getDb();
        var cf = RocksProvider.get().getHandle(RocksProvider.ColumnFamily.DEFAULT);
        TreeMap<Integer, Long> result = new TreeMap<>();

        try (RocksIterator it = db.newIterator(cf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] k = it.key();
                byte[] v = it.value();
                if (k.length == 4 && v.length == 8) {
                    int key = KeyUtil.byteArrayToInt(k);
                    long lastExclusive = KeyUtil.byteArrayToLong(v);
                    if (key != NID_SEQUENCE_KEY) {
                        // key is a patternSequence; lastExclusive is next element sequence for that pattern
                        result.put(key, lastExclusive);
                    }
                }
            }
        }
        return result;
    }

    private long expectedEntityCount(Map<Integer, Long> patternToLastExclusive) {
        long total = 0;
        for (Map.Entry<Integer, Long> e : patternToLastExclusive.entrySet()) {
            long lastExclusive = e.getValue();
            long countForPattern = Math.max(0, lastExclusive - FIRST_ELEMENT_SEQUENCE_OF_PATTERN);
            total += countForPattern;
        }
        return total;
    }

    /**
     * Walks EntityMap in key order and verifies that for each expected longKey
     * there is a matching group:
     * - The first entry for the group has key length 8 and equals the longKey bytes.
     * - All subsequent entries for the group start with the same 8-byte prefix.
     * - Then iteration advances to the next expected longKey group.
     */
    private long validateEntityMapOrderAndCounts(SortedMap<Integer, Long> patternToLastExclusive) throws Exception {
        RocksDB db = RocksProvider.get().getDb();
        var cf = RocksProvider.get().getHandle(RocksProvider.ColumnFamily.ENTITY_MAP);

        long validated = 0;
        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToFirst();

            outer:
            for (Map.Entry<Integer, Long> e : patternToLastExclusive.entrySet()) {
                int pattern = e.getKey();
                long lastExclusive = e.getValue();
                for (long element = FIRST_ELEMENT_SEQUENCE_OF_PATTERN; element < lastExclusive; element++) {
                    long longKey = KeyUtil.patternSequenceElementSequenceToLongKey(pattern, element);
                    byte[] prefix = KeyUtil.longToByteArray(longKey);

                    // Iterator must be valid here
                    if (!it.isValid()) {
                        throw new AssertionError("EntityMap iterator invalid; expected group for longKey=" + String.format("0x%016X", longKey));
                    }

                    // The very first record of a group must be the base entry (8-byte key == prefix)
                    byte[] k = it.key();
                    if (k.length != 8 || !Arrays.equals(k, prefix)) {
                        throw new AssertionError("Expected base entry key[8] for longKey="
                                + String.format("0x%016X", longKey) + " but got keyLen=" + k.length
                                + " key=" + bytesToHex(k));
                    }

                    // Advance to the next entry (may be versions for the same entity or next entity)
                    it.next();

                    // Drain versions: all entries that start with the same 8-byte prefix
                    while (it.isValid()) {
                        byte[] kv = it.key();
                        if (!startsWith(kv, prefix)) {
                            break; // next entity or end
                        }
                        // version entries are longer (13 or 14 etc.); only check prefix match
                        it.next();
                    }

                    validated++;
                }
            }

            // After consuming all expected longKeys, we should be at end (or only trailing non-entity data)
            // If there are still entity-like keys (8+ bytes), fail fast
            while (it.isValid()) {
                byte[] k = it.key();
                if (k.length >= 8) {
                    // Could be unexpected extra entity group
                    throw new AssertionError("EntityMap has more entities than expected; next key=" + bytesToHex(k));
                }
                it.next();
            }
        }
        return validated;
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    private static String bytesToHex(byte[] arr) {
        StringBuilder sb = new StringBuilder(arr.length * 2);
        for (byte b : arr) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // --- Export writers ---

    private long writeSequenceMapEntry(ZipOutputStream zos) throws Exception {
        RocksDB db = RocksProvider.get().getDb();
        var cf = RocksProvider.get().getHandle(RocksProvider.ColumnFamily.DEFAULT);

        ZipEntry entry = new ZipEntry("sequence-map.dat");
        zos.putNextEntry(entry);

        long count = 0;
        try (RocksIterator it = db.newIterator(cf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] keyBytes = it.key();     // 4 bytes (int)
                byte[] valueBytes = it.value(); // 8 bytes (long)
                if (keyBytes.length == 4 && valueBytes.length == 8) {
                    zos.write(keyBytes);
                    zos.write(valueBytes);
                    count++;
                }
            }
        }
        zos.closeEntry();
        return count;
    }

    private long writeEntityMapEntry(ZipOutputStream zos) throws Exception {
        RocksDB db = RocksProvider.get().getDb();
        var cf = RocksProvider.get().getHandle(RocksProvider.ColumnFamily.ENTITY_MAP);

        ZipEntry entry = new ZipEntry("entity-map.dat");
        zos.putNextEntry(entry);

        long count = 0;
        byte[] intBuf = new byte[4];

        try (RocksIterator it = db.newIterator(cf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] keyBytes = it.key();
                byte[] valueBytes = it.value();

                writeInt(zos, intBuf, keyBytes.length);
                zos.write(keyBytes);
                writeInt(zos, intBuf, valueBytes.length);
                zos.write(valueBytes);
                count++;
            }
        }
        zos.closeEntry();
        return count;
    }

    private void writeManifest(ZipOutputStream zos,
                               long seqEntries,
                               long entityMapEntries,
                               long expectedEntityCount,
                               long validatedEntityCount,
                               Map<Integer, Long> patternToLastExclusive) throws Exception {
        Manifest mf = new Manifest();
        Attributes main = mf.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(new Attributes.Name("Created-By"), "RocksExportZipTest");
        main.put(new Attributes.Name("Created-At"), Instant.now().toString());
        main.put(new Attributes.Name("KB-Name"), RocksProvider.get().name());

        // Export counts
        main.put(new Attributes.Name("SequenceMap-Entry-Count"), Long.toString(seqEntries));
        main.put(new Attributes.Name("EntityMap-Entry-Count"), Long.toString(entityMapEntries));

        // Validation summary
        main.put(new Attributes.Name("Expected-Entity-Count"), Long.toString(expectedEntityCount));
        main.put(new Attributes.Name("Validated-Entity-Count"), Long.toString(validatedEntityCount));

        // Brief sequence summary (pattern -> lastExclusive)
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, Long> e : patternToLastExclusive.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append("->").append(e.getValue());
            first = false;
        }
        main.put(new Attributes.Name("SequenceMap-Report"), sb.toString());

        ZipEntry manifestEntry = new ZipEntry("META-INF/MANIFEST.MF");
        zos.putNextEntry(manifestEntry);
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        mf.write(bout);
        byte[] manifestBytes = bout.toByteArray();
        try (ByteArrayInputStream bin = new ByteArrayInputStream(manifestBytes)) {
            bin.transferTo(zos);
        }
        zos.closeEntry();
    }

    private void writeValidationSummary(ZipOutputStream zos,
                                        long expectedEntityCount,
                                        long validatedEntityCount) throws Exception {
        ZipEntry e = new ZipEntry("validation.txt");
        zos.putNextEntry(e);
        String txt = """
                Validation Summary
                ==================
                Expected entity count : %,d
                Validated entity count: %,d

                Status: %s
                """.formatted(
                expectedEntityCount, validatedEntityCount,
                (expectedEntityCount == validatedEntityCount) ? "OK" : "MISMATCH");
        zos.write(txt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // Big-endian
    private static void writeInt(ZipOutputStream zos, byte[] scratch, int v) throws Exception {
        scratch[0] = (byte) (v >>> 24);
        scratch[1] = (byte) (v >>> 16);
        scratch[2] = (byte) (v >>> 8);
        scratch[3] = (byte) (v);
        zos.write(scratch);
    }
}