package dev.ikm.ds.rocks.tasks;

import dev.ikm.ds.rocks.RocksProvider;
import dev.ikm.ds.rocks.spliterator.SpliteratorForLongKeyOfPattern;
import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.ds.rocks.spliterator.LongSpliteratorOfPattern;
import org.eclipse.collections.api.list.ImmutableList;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Orchestrates parallel export of all patterns using structured concurrency,
 * then merges fragment zips into a single final archive with a manifest.
 */
public final class ExportAllPatternsToZip extends TrackingCallable<Path> {

    private final RocksProvider provider;
    private final Path targetZip;

    public ExportAllPatternsToZip(RocksProvider provider,
                                  Path targetZip) {
        this.provider = provider;
        this.targetZip = targetZip;
        updateTitle("Exporting all patterns to ZIP");
    }

    @Override
    protected Path compute() throws Exception {
        ImmutableList<SpliteratorForLongKeyOfPattern> ranges = provider.allPatternSpliterators();
        long totalEntities = ranges.stream()
                .mapToLong(r -> {
                    long est = r.estimateSize();
                    return est == Long.MAX_VALUE ? 1 : Math.max(1, est);
                })
                .reduce(0L, Long::sum);
        totalEntities = Math.max(1, totalEntities);
        updateMessage("Preparing export...");
        updateProgress(0, totalEntities);

        AtomicLong globalDone = new AtomicLong(0L);
        List<ExportPatternToZip.Result> results = new ArrayList<>(ranges.size());

        try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open()) {
            List<StructuredTaskScope.Subtask<ExportPatternToZip.Result>> subtasks = new ArrayList<>(ranges.size());
            for (LongSpliteratorOfPattern r : ranges) {
                String entryName = "pattern-" + r.patternSequence() + ".bin";
                ExportPatternToZip task = new ExportPatternToZip(r.publicIdForPattern(),
                        provider, r, globalDone, totalEntities);
                subtasks.add(scope.fork(task));
            }
            scope.join();
            for (StructuredTaskScope.Subtask<ExportPatternToZip.Result> st : subtasks) {
                results.add(st.get());
            }
        }

        // Merge fragment entries into final ZIP with manifest
        updateMessage("Packaging ZIP...");
        Files.createDirectories(targetZip.getParent());
        try (OutputStream fos = Files.newOutputStream(targetZip);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

            // Manifest
            String manifestText = buildManifest(results);
            ZipEntry manifest = new ZipEntry("manifest.txt");
            zos.putNextEntry(manifest);
            zos.write(manifestText.getBytes());
            zos.closeEntry();

            // Append each fragment’s single STORED entry as-is
            for (ExportPatternToZip.Result r : results) {
                ZipEntry ze = new ZipEntry(r.entryName());
                ze.setMethod(ZipEntry.STORED);
                ze.setSize(r.size());
                ze.setCompressedSize(r.size());
                ze.setCrc(r.crc());
                zos.putNextEntry(ze);
                try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(r.fragmentZip()))) {
                    in.transferTo(zos);
                }
                zos.closeEntry();
            }
        } finally {
            // Cleanup fragment files
            for (ExportPatternToZip.Result r : results) {
                try { Files.deleteIfExists(r.fragmentZip()); } catch (Exception ignore) {}
            }
        }

        updateMessage("Export completed: " + targetZip);
        return targetZip;
    }

    private static String buildManifest(List<ExportPatternToZip.Result> results) {
        StringBuilder sb = new StringBuilder(512);
        try (Formatter f = new Formatter(sb, Locale.ROOT)) {
            long totalEntities = results.stream().mapToLong(ExportPatternToZip.Result::entityCount).sum();
            long totalSize = results.stream().mapToLong(ExportPatternToZip.Result::size).sum();
            f.format("Pattern-Count: %,d%n", results.size());
            f.format("Total-Entity-Count: %,d%n", totalEntities);
            f.format("Total-Bytes: %,d%n", totalSize);
            sb.append(System.lineSeparator());
            sb.append("Entries:").append(System.lineSeparator());
            for (var r : results) {
                f.format("- %s | pattern=%,d | entities=%,d | size=%,d | crc=%08x%n",
                        r.entryName(), r.patternSequence(), r.entityCount(), r.size(), r.crc());
            }
        }
        return sb.toString();
    }
}