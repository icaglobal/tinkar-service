/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.tinkar.service.service;

import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.common.service.TrackingListener;
import dev.ikm.tinkar.service.dto.ExportRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Changeset import and export as {@link AdminJobQueue} jobs: queued behind the reasoner and each
 * other, reporting progress, and watchable after the request that asked for them has gone.
 *
 * <p>An import cannot be cancelled. Stopped part way it would leave some of its entities
 * written; importing merges versions, so re-running a file is safe, but nothing is left half
 * imported by choice. An export can be cancelled, queued or running; a cancelled one leaves no file.
 *
 * <p>A finished export's file is kept for {@code admin.jobs.retention-ms} — as long as its job is
 * remembered — so it can be downloaded later, then deleted. Files left by an earlier run of the
 * service are deleted at startup.
 */
@Component
public class ChangesetJobService {

    private static final Logger log = LoggerFactory.getLogger(ChangesetJobService.class);

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * A finished export.
     *
     * @param fileName the name to offer a downloader, e.g. {@code tinkar-export-TEMPORAL-20261002T153000Z.zip}
     */
    public record ExportResult(File file, String fileName, EntityCountSummary counts) {
    }

    private final TinkarService tinkarService;
    private final AdminJobQueue queue;
    private final Path exportDirectory;
    private final long retentionMs;

    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "changeset-export-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public ChangesetJobService(TinkarService tinkarService, AdminJobQueue queue,
                               @Value("${changeset.export.dir:${java.io.tmpdir}/tinkar-service-exports}") String exportDirectory,
                               @Value("${admin.jobs.retention-ms:3600000}") long retentionMs) throws IOException {
        this.tinkarService = tinkarService;
        this.queue = queue;
        this.exportDirectory = Path.of(exportDirectory);
        this.retentionMs = retentionMs;
        Files.createDirectories(this.exportDirectory);
        deleteLeftovers();
    }

    /**
     * Queues an import of {@code file}, which is deleted once the import has ended.
     *
     * @param name  what to call the import in listings — usually the uploaded file's name
     * @param first attached before the job can start; may be null
     */
    public AdminJobQueue.Job<EntityCountSummary> importChangeset(File file, String name, boolean useMultiPass,
                                                                  AdminJobQueue.Watcher<EntityCountSummary> first) {
        AdminJobQueue.Job<EntityCountSummary> job = queue.submit(
                AdminJobQueue.Kind.IMPORT, "Import " + name, false,
                running -> tinkarService.importChangeset(file, useMultiPass, progressOf(running)),
                first);
        job.whenFinished(outcome -> deleteQuietly(file.toPath()));
        return job;
    }

    /**
     * Queues an export.
     *
     * @param request which entities; validated here, so a bad request is refused before it queues
     * @param first   attached before the job can start; may be null
     * @throws IllegalArgumentException if the request cannot be run
     */
    public AdminJobQueue.Job<ExportResult> export(ExportRequest request,
                                                  AdminJobQueue.Watcher<ExportResult> first) {
        request.validate();
        AdminJobQueue.Job<ExportResult> job = queue.submit(
                AdminJobQueue.Kind.EXPORT, "Export " + request.describe(), true,
                running -> runExport(running, request),
                first);
        job.whenFinished(outcome -> {
            if (outcome.result() != null) {
                cleanup.schedule(() -> deleteQuietly(outcome.result().file().getParentFile().toPath()),
                        retentionMs, TimeUnit.MILLISECONDS);
            }
        });
        return job;
    }

    /** The file a finished export wrote, if its job is still remembered and succeeded. */
    public Optional<ExportResult> exportResult(String jobId) {
        return queue.find(jobId)
                .filter(job -> job.kind() == AdminJobQueue.Kind.EXPORT)
                .flatMap(AdminJobQueue.Job::outcome)
                .map(AdminJobQueue.Outcome::result)
                .filter(ExportResult.class::isInstance)
                .map(ExportResult.class::cast)
                .filter(result -> result.file().isFile());
    }

    private ExportResult runExport(AdminJobQueue.Job<ExportResult> job, ExportRequest request) throws Exception {
        // A folder per job, so concurrent names never collide and cleanup removes one unit.
        Path folder = Files.createDirectories(exportDirectory.resolve(job.id()));
        String fileName = "tinkar-export-" + request.type() + "-"
                + FILE_TIME.format(java.time.Instant.now()) + ".zip";
        File target = folder.resolve(fileName).toFile();
        try {
            EntityCountSummary counts = tinkarService.exportEntities(target, request, progressOf(job), job::isCancelled);
            return new ExportResult(target, fileName, counts);
        } catch (Exception | Error e) {
            deleteQuietly(folder); // a partial zip is worse than none
            throw e;
        }
    }

    /** Forwards a loader's or exporter's progress to the job's watchers. */
    private static TrackingListener<EntityCountSummary> progressOf(AdminJobQueue.Job<?> job) {
        return new TrackingListener<>() {
            private long done;
            private long total;
            private String message = "";

            @Override
            public void updateValue(EntityCountSummary result) {
            }

            @Override
            public synchronized void updateMessage(String newMessage) {
                if (newMessage == null || newMessage.equals(message)) {
                    return;
                }
                message = newMessage;
                job.progress(done, total, message);
            }

            @Override
            public void updateTitle(String title) {
            }

            @Override
            public synchronized void updateProgress(double workDone, double max) {
                done = (long) workDone;
                total = (long) max;
                job.progress(done, total, message);
            }
        };
    }

    private void deleteLeftovers() {
        try (Stream<Path> entries = Files.list(exportDirectory)) {
            entries.forEach(ChangesetJobService::deleteQuietly);
        } catch (IOException e) {
            log.warn("Could not clear old exports in {}: {}", exportDirectory, e.toString());
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> entry.toFile().delete());
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", path, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        cleanup.shutdownNow();
    }
}
