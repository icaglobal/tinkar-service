package dev.ikm.ds.rocks.tasks;

import dev.ikm.ds.rocks.RocksProvider;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.ds.rocks.spliterator.LongSpliteratorOfPattern;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes all entities for a single pattern into a STORED Zip entry.
 * Produces a temp zip fragment file to be merged later into a final archive.
 */
public final class ExportPatternToZip extends TrackingCallable<ExportPatternToZip.Result> {

    public record Result(
            int patternSequence,
            Path fragmentZip,     // temp zip with one STORED entry
            String entryName,
            long entityCount,
            long size,
            long crc
    ) {}

    private final RocksProvider provider;
    private final LongSpliteratorOfPattern range;
    private final AtomicLong globalProgressDone;
    private final long globalProgressMax;
    private final String patternName;
    private final PublicId patternPublicId;

    public ExportPatternToZip(PublicId patternPublicId,
                              RocksProvider provider,
                              LongSpliteratorOfPattern range,
                              AtomicLong globalProgressDone,
                              long globalProgressMax) {
        this.provider = provider;
        this.range = range;
        this.globalProgressDone = globalProgressDone;
        this.globalProgressMax = globalProgressMax;
        this.patternName = PrimitiveData.text(range.patternSequence());
        this.patternPublicId = patternPublicId;
        updateTitle("Export pattern " + patternName);
    }

    @Override
    protected Result compute() throws Exception {
        addToTotalWork(Math.max(1, estimate(range)));

        updateMessage("Preparing " + patternName + " export...");

        Path fragment = Files.createTempFile("pattern-" + patternName + "-", ".zip");
        long size;
        long crcValue;
        long count;

        try (OutputStream fos = Files.newOutputStream(fragment);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

            // We will compute CRC and size by streaming into a counting+crc sink first,
            // then write a STORED entry in a second pass from the buffered bytes.
            // To avoid double buffering large data, we stream directly to a temp file entry
            // by writing an uncompressed STORED entry with known size/crc. To do that,
            // we must pre-compute size+crc. So we do a first pass to a temp raw file.
            Path rawTemp = Files.createTempFile("pattern-" + range.patternSequence() + "-", ".bin");
            CRC32 crc32 = new CRC32();
            AtomicLong byteCount = new AtomicLong();
            AtomicLong entityCount = new AtomicLong();

            // First pass: write raw concatenation format [entityLength(int)][entityBytes]...
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(rawTemp))) {
                streamEntities(out, crc32, byteCount, entityCount);
            } catch (Throwable t) {
                try { Files.deleteIfExists(rawTemp); } catch (Exception ignore) {}
                throw t;
            }

            size = byteCount.get();
            crcValue = crc32.getValue();
            count = entityCount.get();

            // Second pass: add STORED entry into fragment zip
            ZipEntry entry = new ZipEntry(entryName());
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(size);
            entry.setCompressedSize(size);
            entry.setCrc(crcValue);
            zos.putNextEntry(entry);
            try (var in = Files.newInputStream(rawTemp)) {
                in.transferTo(zos);
            } finally {
                zos.closeEntry();
                try { Files.deleteIfExists(rawTemp); } catch (Exception ignore) {}
            }
        }

        updateMessage(patternName + " complete (" + count + " entities)");
        return new Result(range.patternSequence(), fragment, entryName(), count, size, crcValue);
    }

    private String entryName() {
        // You can customize naming here; if you can resolve a UUID of the pattern, prefer that:
        // UUID patternUuid = resolvePatternUuid(range.patternSequence());
        // return "pattern-" + range.patternSequence() + "-" + patternUuid + ".bin";
        String patternNameForEntry = patternName.trim(); //.replaceAll("\\s+", "-");
        patternNameForEntry = patternNameForEntry.replace("/", "-"); // also replace '/' to avoid directory separators
        patternNameForEntry = patternNameForEntry + "-" + patternPublicId.toString();
        return "pattern-" + patternNameForEntry + ".bin";
    }

    private void streamEntities(OutputStream out,
                                CRC32 crc32,
                                AtomicLong byteCount,
                                AtomicLong entityCount) throws IOException {
        // Format: [4-byte length][entityBytes] repeated. This is a raw container inside the entry.
        // If you need a different layout, adjust here.
        final byte[] lenBuf = new byte[4];

        provider.scanEntitiesInRange(range, (entityBytes, nid) -> {
            if (Thread.currentThread().isInterrupted() || isCancelled()) {
                throw new RuntimeException(new InterruptedException("Cancelled"));
            }
            try {
                int len = entityBytes.length;
                lenBuf[0] = (byte) (len >>> 24);
                lenBuf[1] = (byte) (len >>> 16);
                lenBuf[2] = (byte) (len >>> 8);
                lenBuf[3] = (byte) (len);

                out.write(lenBuf);
                out.write(entityBytes);

                crc32.update(lenBuf);
                crc32.update(entityBytes);
                byteCount.addAndGet(4L + len);
                long c = entityCount.incrementAndGet();

                // Progress updates: reflect into global + local
                long done = globalProgressDone.incrementAndGet();
                if ((done & 0x3FFF) == 0) {
                    updateProgress(done, globalProgressMax);
                    if (updateIntervalElapsed()) {
                        updateMessage("Exporting... " + c + " entities for pattern " + range.patternSequence());
                    }
                }
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        });
    }

    private static long estimate(LongSpliteratorOfPattern r) {
        long est = r.estimateSize();
        return est == Long.MAX_VALUE ? 1 : Math.max(1, est);
    }
}