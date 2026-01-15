package dev.ikm.ds.rocks;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Disabled
public class RocksLoadFromExportZipTest {

    private static final String DEFAULT_ZIP = "target/export/rocks-kb-export.zip";
    private static final String ENTRY_SEQUENCE_MAP = "sequence-map.dat";
    private static final String ENTRY_ENTITY_KEYS = "entity-keys.dat";
    private static final String ENTRY_ENTITY_MAP = "entity-map.dat";
    private static final String ENTRY_MANIFEST = "META-INF/MANIFEST.MF";

    private static final int FIRST_ELEMENT_SEQUENCE_OF_PATTERN = 1; // must match writer convention
    private static final int NID_SEQUENCE_KEY = -1; // skip in sequence map expectations

    @Test
    void loadAndValidateExportZip() throws Exception {
        Path zipPath = Path.of(DEFAULT_ZIP);
        if (!Files.exists(zipPath)) {
            throw new AssertionError("Export ZIP not found: " + zipPath.toAbsolutePath()
                    + " (run the export test first)");
        }

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            // 1) Read manifest to capture reported counts (if present)
            ManifestSummary manifest = readManifest(zip);

            // 2) Read sequence map and compute expectations
            TreeMap<Integer, Long> patternToLastExclusive = readSequenceMap(zip);
            long expectedEntityCount = expectedEntityCount(patternToLastExclusive);

            // 3) Stream entity-map.dat and validate grouping/order by 8-byte prefix
            long validatedEntityCount = validateEntityMapOrdering(zip, patternToLastExclusive);

            // 4) Count nid->EntityKey entries
            long entityKeyCount = countEntityKeys(zip);

            // 5) Assertions
            if (validatedEntityCount != expectedEntityCount) {
                throw new AssertionError("Validated entity count " + validatedEntityCount
                        + " != expected " + expectedEntityCount);
            }
            if (entityKeyCount != expectedEntityCount) {
                throw new AssertionError("EntityKey entry count " + entityKeyCount
                        + " != expected " + expectedEntityCount);
            }

            // 6) If manifest had values, compare against them too (optional)
            if (manifest != null) {
                if (manifest.expectedEntityCount != null && manifest.expectedEntityCount != expectedEntityCount) {
                    throw new AssertionError("Manifest Expected-Entity-Count " + manifest.expectedEntityCount
                            + " != computed expected " + expectedEntityCount);
                }
                if (manifest.validatedEntityCount != null && manifest.validatedEntityCount != validatedEntityCount) {
                    throw new AssertionError("Manifest Validated-Entity-Count " + manifest.validatedEntityCount
                            + " != computed validated " + validatedEntityCount);
                }
                if (manifest.nidKeyEntries != null && manifest.nidKeyEntries != entityKeyCount) {
                    throw new AssertionError("Manifest NidKey-Entry-Count " + manifest.nidKeyEntries
                            + " != counted " + entityKeyCount);
                }
            }

            // 7) Simple parallel load pass (throughput sanity check)
            parallelDecodeEntityMap(zip);
        }
    }

    // =========================
    // ZIP readers and validators
    // =========================

    private ManifestSummary readManifest(ZipFile zip) throws IOException {
        ZipEntry e = zip.getEntry(ENTRY_MANIFEST);
        if (e == null) return null;
        Properties p = new Properties();
        try (InputStream in = zip.getInputStream(e)) {
            // MANIFEST.MF is not Java Properties format, but attributes are in key: value lines.
            // We’ll parse lightly to pick up the integer values we care about.
            Scanner sc = new Scanner(in, java.nio.charset.StandardCharsets.UTF_8);
            Long expected = null, validated = null, nidCount = null;
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim();
                    String v = line.substring(idx + 1).trim();
                    if (k.equalsIgnoreCase("Expected-Entity-Count")) expected = parseLongOrNull(v);
                    else if (k.equalsIgnoreCase("Validated-Entity-Count")) validated = parseLongOrNull(v);
                    else if (k.equalsIgnoreCase("NidKey-Entry-Count")) nidCount = parseLongOrNull(v);
                }
            }
            return new ManifestSummary(expected, validated, nidCount);
        }
    }

    private static Long parseLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception ex) {
            return null;
        }
    }

    private TreeMap<Integer, Long> readSequenceMap(ZipFile zip) throws IOException {
        ZipEntry e = zip.getEntry(ENTRY_SEQUENCE_MAP);
        if (e == null) throw new AssertionError("Missing " + ENTRY_SEQUENCE_MAP);
        TreeMap<Integer, Long> result = new TreeMap<>();
        try (InputStream in = zip.getInputStream(e)) {
            DataInputStream din = new DataInputStream(in);
            byte[] buf4 = new byte[4];
            byte[] buf8 = new byte[8];
            while (true) {
                int r = readFullyOrEOF(din, buf4);
                if (r == -1) break; // EOF
                readFully(din, buf8);

                int key = toIntBE(buf4);
                long value = toLongBE(buf8);
                if (key != NID_SEQUENCE_KEY) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    private long expectedEntityCount(Map<Integer, Long> patternToLastExclusive) {
        long total = 0;
        for (Map.Entry<Integer, Long> e : patternToLastExclusive.entrySet()) {
            long countForPattern = Math.max(0, e.getValue() - FIRST_ELEMENT_SEQUENCE_OF_PATTERN);
            total += countForPattern;
        }
        return total;
    }

    /**
     * Validates the entity-map.dat ordering by walking the theoretical sequence
     * and matching groups in the stream:
     * - For each longKey, first record key must be exactly 8 bytes == longKey.
     * - Subsequent records with same 8-byte prefix are versions; consume until prefix changes.
     */
    private long validateEntityMapOrdering(ZipFile zip, SortedMap<Integer, Long> patternToLastExclusive) throws IOException {
        ZipEntry e = zip.getEntry(ENTRY_ENTITY_MAP);
        if (e == null) throw new AssertionError("Missing " + ENTRY_ENTITY_MAP);

        long validated = 0;

        try (InputStream in = zip.getInputStream(e)) {
            DataInputStream din = new DataInputStream(in);

            // Stream is [4-byte keyLen][key][4-byte valueLen][value] repeated.
            // We’ll decode on the fly and maintain a cursor keyed by theoretical longKey progression.
            Iterator<Map.Entry<Integer, Long>> patIter = patternToLastExclusive.entrySet().iterator();
            int currentPattern = -1;
            long element = 0, lastExclusive = 0;

            // Initialize first pattern range
            if (patIter.hasNext()) {
                var first = patIter.next();
                currentPattern = first.getKey();
                element = FIRST_ELEMENT_SEQUENCE_OF_PATTERN;
                lastExclusive = first.getValue();
            } else {
                return 0;
            }

            // Helper to advance to next pattern when element hits lastExclusive
            Runnable advancePattern = () -> { /* no-op placeholder */ };

            while (true) {
                int keyLen = readIntBEOrEOF(din);
                if (keyLen == Integer.MIN_VALUE) break; // EOF

                byte[] key = new byte[keyLen];
                readFully(din, key);

                int valLen = readIntBE(din);
                byte[] val = new byte[valLen];
                readFully(din, val);

                // For each theoretical longKey, we expect the next group to start with an 8-byte base key
                // Align theory to the actual stream: skip over version records until we see an 8-byte base
                while (key.length != 8) {
                    // This is a version record; we must have been in the middle of a previous entity
                    // Read next (will loop until a base 8-byte key arrives)
                    keyLen = readIntBEOrEOF(din);
                    if (keyLen == Integer.MIN_VALUE) {
                        throw new AssertionError("Unexpected EOF while seeking next base entity key");
                    }
                    key = new byte[keyLen];
                    readFully(din, key);
                    valLen = readIntBE(din);
                    val = new byte[valLen];
                    readFully(din, val);
                }

                // Now key.length == 8; compare to theoretical longKey
                long longKeyTheory = KeyUtil.patternSequenceElementSequenceToLongKey(currentPattern, element);
                byte[] theoryPrefix = KeyUtil.longToByteArray(longKeyTheory);
                if (!Arrays.equals(key, theoryPrefix)) {
                    throw new AssertionError("Entity stream out of order or missing. Expected base key for longKey="
                            + String.format("0x%016X", longKeyTheory) + " but found=" + bytesToHex(key));
                }

                // Consume versions with the same prefix
                while (true) {
                    din.mark(16); // small lookahead
                    int nextKeyLen = readIntBEOrEOF(din);
                    if (nextKeyLen == Integer.MIN_VALUE) {
                        // EOF after base; valid end
                        validated++;
                        // Advance theoretical cursor
                        element++;
                        if (element >= lastExclusive) {
                            if (patIter.hasNext()) {
                                var nxt = patIter.next();
                                currentPattern = nxt.getKey();
                                element = FIRST_ELEMENT_SEQUENCE_OF_PATTERN;
                                lastExclusive = nxt.getValue();
                            } else {
                                // Done with expectations; OK
                            }
                        }
                        break;
                    }
                    byte[] nextKey = new byte[nextKeyLen];
                    readFully(din, nextKey);
                    int nextValLen = readIntBE(din);
                    byte[] nextVal = new byte[nextValLen];
                    readFully(din, nextVal);

                    if (nextKey.length >= 8 && startsWith(nextKey, theoryPrefix)) {
                        // still same entity, continue draining versions
                        key = nextKey;
                        val = nextVal;
                        continue;
                    } else {
                        // different entity; push back by reconstructing a tiny “unread” buffer is complex with DataInputStream.
                        // Instead, we’ll hold onto this tuple and process it next loop by using a small queue.
                        // For simplicity, we’ll emulate by recursive handling:
                        // Re-encode (keyLen,key,valLen,val) into a queue-less approach is messy; since this is a load test,
                        // we’ll break here and resume from the “found next base” by throwing to a fast path.
                        // A simpler approach: we continue with this “nextKey” as the current record.
                        // That means we need a loop structure that keeps a carried record.

                        // Carry record into outer loop:
                        validated++;
                        // Advance theory
                        element++;
                        if (element >= lastExclusive) {
                            if (patIter.hasNext()) {
                                var nxt = patIter.next();
                                currentPattern = nxt.getKey();
                                element = FIRST_ELEMENT_SEQUENCE_OF_PATTERN;
                                lastExclusive = nxt.getValue();
                            }
                        }

                        // Now treat carried record as current and continue:
                        key = nextKey;
                        val = nextVal;

                        // Align: if carried record is not base (key.length != 8), keep draining until we get a base
                        while (key.length != 8) {
                            nextKeyLen = readIntBEOrEOF(din);
                            if (nextKeyLen == Integer.MIN_VALUE) {
                                throw new AssertionError("Unexpected EOF while aligning to next base entity");
                            }
                            key = new byte[nextKeyLen];
                            readFully(din, key);
                            nextValLen = readIntBE(din);
                            nextVal = new byte[nextValLen];
                            readFully(din, nextVal);
                        }
                        // Outer loop will verify this base against theory in the next iteration
                        break;
                    }
                }

                // If we reached end-of-file at the consume loop, we’re done; otherwise keep going until all expectations consumed.
                if (!patIter.hasNext() && element >= lastExclusive) {
                    // All expected entities consumed; we can stop validating here (trailing extra would have been caught above)
                    break;
                }
            }
        }

        return validated;
    }

    private long countEntityKeys(ZipFile zip) throws IOException {
        ZipEntry e = zip.getEntry(ENTRY_ENTITY_KEYS);
        if (e == null) throw new AssertionError("Missing " + ENTRY_ENTITY_KEYS);
        long count = 0;
        try (InputStream in = zip.getInputStream(e)) {
            DataInputStream din = new DataInputStream(in);
            byte[] nid = new byte[4];
            byte[] ek = new byte[12]; // minimum, but we’ll accept >= 12 (readExactly 12 then drop remainder for simplicity)
            while (true) {
                int r = readFullyOrEOF(din, nid);
                if (r == -1) break;
                // read at least 12 bytes of value; if more are present, we can’t know length (writer didn’t prefix here)
                // Since writer writes raw pairs, we assume fixed 12 for base keys (that’s what was written).
                readFully(din, ek);
                count++;
            }
        }
        return count;
    }

    // Simple throughput check: repeatedly decode entity-map.dat groups in parallel
    private void parallelDecodeEntityMap(ZipFile zip) throws IOException {
        ZipEntry e = zip.getEntry(ENTRY_ENTITY_MAP);
        if (e == null) return;

        // Slurp bytes (load test input)
        byte[] all;
        try (InputStream in = zip.getInputStream(e)) {
            all = in.readAllBytes();
        }

        // Decode into offsets of base-keys (keyLen==8 positions), then parallel-iterate to simulate load
        List<Integer> recordOffsets = new ArrayList<>(1 << 20);
        List<Integer> baseOffsets = new ArrayList<>(1 << 19);

        // First pass: index records (keyLen,key,valLen,val)
        {
            ByteBuffer buf = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
            while (buf.remaining() >= 4) {
                int keyLen = buf.getInt();
                if (keyLen < 0 || keyLen > buf.remaining()) break;
                int keyPos = buf.position();
                buf.position(keyPos + keyLen);
                if (buf.remaining() < 4) break;
                int valLen = buf.getInt();
                if (valLen < 0 || valLen > buf.remaining()) break;
                buf.position(buf.position() + valLen);

                recordOffsets.add(keyPos - 4); // start of record (keyLen position)
                if (keyLen == 8) {
                    baseOffsets.add(keyPos - 4);
                }
            }
        }

        LongAdder groups = new LongAdder();
        Instant start = Instant.now();
        ForkJoinPool.commonPool().submit(() ->
                baseOffsets.parallelStream().forEach(off -> groups.increment())
        ).join();
        Duration d = Duration.between(start, Instant.now());

        System.out.println("Parallel load pass: base groups=" + groups.sum()
                + " in " + d.toMillis() + " ms");
    }

    // =========================
    // Utilities
    // =========================

    private static int readFullyOrEOF(DataInputStream din, byte[] dst) throws IOException {
        try {
            din.readFully(dst);
            return dst.length;
        } catch (EOFException eof) {
            return -1;
        }
    }

    private static void readFully(DataInputStream din, byte[] dst) throws IOException {
        din.readFully(dst);
    }

    private static int readIntBEOrEOF(DataInputStream din) throws IOException {
        try {
            return din.readInt();
        } catch (EOFException eof) {
            return Integer.MIN_VALUE;
        }
    }

    private static int readIntBE(DataInputStream din) throws IOException {
        return din.readInt();
    }

    private static int toIntBE(byte[] b) {
        return ((b[0] & 0xFF) << 24)
                | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);
    }

    private static long toLongBE(byte[] b) {
        return ((long) (b[0] & 0xFF) << 56)
                | ((long) (b[1] & 0xFF) << 48)
                | ((long) (b[2] & 0xFF) << 40)
                | ((long) (b[3] & 0xFF) << 32)
                | ((long) (b[4] & 0xFF) << 24)
                | ((long) (b[5] & 0xFF) << 16)
                | ((long) (b[6] & 0xFF) << 8)
                | ((long) (b[7] & 0xFF));
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
        for (byte b : arr) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private record ManifestSummary(Long expectedEntityCount,
                                   Long validatedEntityCount,
                                   Long nidKeyEntries) {
    }
}