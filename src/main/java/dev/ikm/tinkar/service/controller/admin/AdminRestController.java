package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.service.dto.ChangesetJobResult;
import dev.ikm.tinkar.service.dto.ExportRequest;
import dev.ikm.tinkar.service.dto.JobEvent;
import dev.ikm.tinkar.service.service.AdminJobQueue;
import dev.ikm.tinkar.service.service.ChangesetJobService;
import dev.ikm.tinkar.service.dto.ReasonerPhaseEvent;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.service.dto.ReasonerRunEvent;
import dev.ikm.tinkar.service.service.ReasonerRunManager;
import dev.ikm.tinkar.service.service.TinkarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Tier 3: Admin / Data Management — REST controller.
 *
 * Operations for importing changesets, exporting entity data,
 * and running the reasoner classification pipeline.
 * Target audience: platform operators, DevOps, CI/CD pipelines.
 */
@RestController
@RequestMapping("/api/ike/admin")
@Tag(name = "IKE Admin (Tier 3)", description = "Data management operations: import changesets, export entities, and reasoner classification.")
public class AdminRestController {
    private static final Logger log = LoggerFactory.getLogger(AdminRestController.class);

    /** How often an open stream is probed for a disconnected client. */
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;

    /** Sends heartbeats for open streams. */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rest-reasoner-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * How long a stream may stay open, in milliseconds.
     *
     * <p>Only the stream: when it expires the caller is detached and the run carries on, and the
     * caller can reconnect with GET /reasoner/stream. Configured by
     * {@code reasoner.stream.timeout-ms}; without one, Spring's async default of about 30 seconds
     * would close every real run's stream.
     */
    private final long reasonerStreamTimeoutMs;

    private final TinkarService tinkarService;

    private final ReasonerRunManager reasonerRuns;

    private final AdminJobQueue jobs;

    private final ChangesetJobService changesets;

    public AdminRestController(TinkarService tinkarService,
                               ReasonerRunManager reasonerRuns,
                               AdminJobQueue jobs,
                               ChangesetJobService changesets,
                               @Value("${reasoner.stream.timeout-ms:14400000}") long reasonerStreamTimeoutMs) {
        this.tinkarService = tinkarService;
        this.reasonerRuns = reasonerRuns;
        this.jobs = jobs;
        this.changesets = changesets;
        this.reasonerStreamTimeoutMs = reasonerStreamTimeoutMs;
    }

    @Operation(summary = "Import a changeset",
            description = "Imports a Tinkar changeset from a protobuf ZIP file and waits for it. The import is "
                    + "queued behind any running reasoner, import or export, and cannot be cancelled. "
                    + "Uses multi-pass import by default to handle forward references. "
                    + "POST /import/stream does the same and reports progress.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Import finished; success or failure",
                    content = @Content(schema = @Schema(implementation = EntityCountSummaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid file or parameters")
    })
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EntityCountSummaryResponse> importChangeset(
            @Parameter(description = "Protobuf ZIP file to import", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Use multi-pass import to resolve forward references (default: true)")
            @RequestParam(required = false, defaultValue = "true") boolean useMultiPass) {
        File upload;
        try {
            upload = saveUpload(file);
        } catch (IOException e) {
            return ResponseEntity.ok(EntityCountSummaryResponse.error("Could not read the upload: " + e.getMessage()));
        }
        CompletableFuture<AdminJobQueue.Outcome<EntityCountSummary>> finished = new CompletableFuture<>();
        changesets.importChangeset(upload, uploadName(file), useMultiPass, finished::complete);
        AdminJobQueue.Outcome<EntityCountSummary> outcome = await(finished);
        if (outcome == null || outcome.state() != AdminJobQueue.State.SUCCEEDED) {
            return ResponseEntity.ok(EntityCountSummaryResponse.error(
                    outcome == null ? "Interrupted while waiting for the import" : outcome.errorMessage()));
        }
        EntityCountSummary counts = outcome.result();
        return ResponseEntity.ok(EntityCountSummaryResponse.success(
                counts.conceptCount(), counts.semanticCount(), counts.patternCount(), counts.stampCount()));
    }

    @Operation(summary = "Import a changeset, streaming progress",
            description = "Queues an import of the uploaded changeset and returns a text/event-stream: a 'job' event, "
                    + "'queued' events while it waits, 'started', 'progress' events, then one 'result'. Closing the "
                    + "stream only stops watching; reconnect with GET /jobs/{jobId}/stream.")
    @PostMapping(value = "/import/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> importChangesetStreaming(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "true") boolean useMultiPass) throws IOException {
        File upload = saveUpload(file);
        return this.<EntityCountSummary>jobStream(watcher -> Optional.of(
                        changesets.importChangeset(upload, uploadName(file), useMultiPass, watcher)),
                (jobId, outcome) -> ChangesetJobResult.ofImport(jobId, outcome));
    }

    @Operation(summary = "Export entities",
            description = "Exports FULL, TEMPORAL (a time range — Komet's 'Change set') or MEMBERSHIP, waits, and "
                    + "returns the changeset ZIP. Queued behind any running reasoner, import or export. "
                    + "POST /export/stream does the same and reports progress.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The exported ZIP"),
            @ApiResponse(responseCode = "400", description = "The request cannot be run"),
            @ApiResponse(responseCode = "500", description = "The export failed",
                    content = @Content(schema = @Schema(implementation = ChangesetJobResult.class)))
    })
    @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportEntities(@RequestBody ExportRequest request) {
        CompletableFuture<AdminJobQueue.Outcome<ChangesetJobService.ExportResult>> finished = new CompletableFuture<>();
        AdminJobQueue.Job<ChangesetJobService.ExportResult> job;
        try {
            job = changesets.export(request, finished::complete);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        AdminJobQueue.Outcome<ChangesetJobService.ExportResult> outcome = await(finished);
        if (outcome == null || outcome.state() != AdminJobQueue.State.SUCCEEDED) {
            return ResponseEntity.status(500).body(outcome == null
                    ? Map.of("error", "Interrupted while waiting for the export")
                    : ChangesetJobResult.ofExport(job.id(), outcome, null));
        }
        return download(outcome.result());
    }

    @Operation(summary = "Export entities, streaming progress",
            description = "Queues an export and returns a text/event-stream like POST /import/stream. Its 'result' "
                    + "carries downloadUrl, where the ZIP can be fetched for an hour.")
    @PostMapping(value = "/export/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> exportEntitiesStreaming(@RequestBody ExportRequest request) {
        try {
            request.validate();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return this.<ChangesetJobService.ExportResult>jobStream(watcher -> Optional.of(changesets.export(request, watcher)),
                (jobId, outcome) -> ChangesetJobResult.ofExport(jobId, outcome, downloadUrl(jobId)));
    }

    @Operation(summary = "Download an export",
            description = "The ZIP a finished export wrote. Kept for an hour after the export finished.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The exported ZIP"),
            @ApiResponse(responseCode = "404", description = "No such export, it failed, or it has expired")
    })
    @GetMapping("/export/{jobId}/file")
    public ResponseEntity<?> downloadExport(@PathVariable String jobId) {
        return changesets.exportResult(jobId)
                .<ResponseEntity<?>>map(this::download)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "No finished export " + jobId + " — it may have failed or expired")));
    }

    @Operation(summary = "List jobs",
            description = "Reasoner, import and export jobs still remembered: queued, running, and those finished "
                    + "within the last hour, oldest first.")
    @GetMapping("/jobs")
    public List<AdminJobQueue.Summary> listJobs() {
        return jobs.list();
    }

    @Operation(summary = "Watch a job",
            description = "Attaches to any job by id — queued, running or recently finished — and streams it as "
                    + "POST /import/stream does, replaying what has happened so far.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event stream opened"),
            @ApiResponse(responseCode = "404", description = "No such job, or it has expired")
    })
    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<?> watchJob(@PathVariable String jobId) {
        Optional<AdminJobQueue.Job<?>> found = jobs.find(jobId);
        if (found.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No job " + jobId + " — it may have expired"));
        }
        AdminJobQueue.Job job = found.get();
        return jobStream(watcher -> {
            jobs.watch(job, watcher);
            return Optional.of(job);
        }, (id, outcome) -> resultOf(job, (AdminJobQueue.Outcome) outcome));
    }

    @Operation(summary = "Cancel a job",
            description = "Asks a queued or running reasoner run or export to stop, and returns at once; watchers get "
                    + "a result with cancelled=true when it has. A queued job ends without running. Imports cannot be "
                    + "cancelled — stopped part way they would leave some entities written.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Cancel requested"),
            @ApiResponse(responseCode = "404", description = "No such job, or it has expired"),
            @ApiResponse(responseCode = "409", description = "The job has finished, or is an import")
    })
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Map<String, String>> cancelJob(@PathVariable String jobId) {
        Optional<AdminJobQueue.Job<?>> found = jobs.find(jobId);
        if (found.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No job " + jobId + " — it may have expired"));
        }
        try {
            if (!jobs.cancel(found.get())) {
                return ResponseEntity.status(409).body(Map.of("error", "Job " + jobId + " has already finished"));
            }
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.accepted().body(Map.of("status", "Cancel requested"));
    }

    /** The {@code result} event for a job of any kind. */
    @SuppressWarnings("unchecked")
    private Object resultOf(AdminJobQueue.Job<?> job, AdminJobQueue.Outcome<?> outcome) {
        return switch (job.kind()) {
            case IMPORT -> ChangesetJobResult.ofImport(job.id(), (AdminJobQueue.Outcome<EntityCountSummary>) outcome);
            case EXPORT -> ChangesetJobResult.ofExport(job.id(),
                    (AdminJobQueue.Outcome<ChangesetJobService.ExportResult>) outcome, downloadUrl(job.id()));
            case REASONER -> ReasonerResultsResponse.from(ReasonerRunManager.toOutcome(
                    (AdminJobQueue.Outcome<dev.ikm.tinkar.reasoner.service.ClassifierResults>) outcome));
        };
    }

    private ResponseEntity<Resource> download(ChangesetJobService.ExportResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.fileName() + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(result.file().length())
                .body(new FileSystemResource(result.file()));
    }

    private static String downloadUrl(String jobId) {
        return "/api/ike/admin/export/" + jobId + "/file";
    }

    /** Copies an upload to a temp file, which the import job deletes when it ends. */
    private static File saveUpload(MultipartFile file) throws IOException {
        File upload = Files.createTempFile("tinkar-import-", ".zip").toFile();
        file.transferTo(upload);
        return upload;
    }

    private static String uploadName(MultipartFile file) {
        return file.getOriginalFilename() == null ? "upload.zip" : file.getOriginalFilename();
    }

    private static <T> T await(CompletableFuture<T> finished) {
        try {
            return finished.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    @Operation(summary = "Run the reasoner",
            description = "Runs the full reasoner classification pipeline and waits for it: "
                    + "init -> extractData -> loadData -> computeInferences -> writeInferredResults. "
                    + "If a run is already going, waits for that one instead of starting another. "
                    + "The run belongs to the server: a caller that gives up waiting does not stop it.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reasoner finished; success, cancelled, or failed",
                    content = @Content(schema = @Schema(implementation = ReasonerResultsResponse.class)))
    })
    @PostMapping("/reasoner")
    public ResponseEntity<ReasonerResultsResponse> runReasoner() {
        CompletableFuture<ReasonerRunManager.Outcome> finished = new CompletableFuture<>();
        reasonerRuns.runOrAttach(new ReasonerRunManager.Watcher() {
            @Override
            public void onPhase(ReasonerRunManager.Phase phase) {
            }

            @Override
            public void onFinished(ReasonerRunManager.Outcome outcome) {
                finished.complete(outcome);
            }
        });
        try {
            return ResponseEntity.ok(ReasonerResultsResponse.from(finished.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.ok(ReasonerResultsResponse.error("Interrupted while waiting for the reasoner"));
        } catch (ExecutionException e) {
            return ResponseEntity.ok(ReasonerResultsResponse.error(e.getCause().toString()));
        }
    }

    @Operation(summary = "Run the reasoner, streaming progress",
            description = "Starts a classification, or attaches to the one already running, and returns a "
                    + "text/event-stream: a 'run' event saying which, one 'phase' event per completed phase "
                    + "(replayed from the start when attaching), then a single 'result' event. "
                    + "Closing the stream only stops watching — the run carries on. Reconnect with "
                    + "GET /reasoner/stream; stop the run with POST /reasoner/cancel.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event stream opened")
    })
    @PostMapping(value = "/reasoner/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> runReasonerStreaming() {
        return stream(watcher -> Optional.of(reasonerRuns.runOrAttach(watcher).run()));
    }

    @Operation(summary = "Watch the reasoner",
            description = "Attaches to the running classification, or replays the most recent one if it has "
                    + "finished, as the same event stream POST /reasoner/stream returns. Never starts a run. "
                    + "Use it to get back to a run after leaving the page or closing Komet.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event stream opened"),
            @ApiResponse(responseCode = "204", description = "No run since the server started")
    })
    @GetMapping(value = "/reasoner/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> watchReasoner() {
        return stream(reasonerRuns::watch);
    }

    @Operation(summary = "Cancel the running reasoner",
            description = "Asks the running classification to stop, and returns at once. Anyone watching "
                    + "gets a 'result' event with cancelled=true once it has. A run already writing its "
                    + "inferred results finishes the write rather than leave it half done, and reports "
                    + "success.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Cancel requested"),
            @ApiResponse(responseCode = "409", description = "No classification is running")
    })
    @PostMapping("/reasoner/cancel")
    public ResponseEntity<Map<String, String>> cancelReasoner() {
        if (!reasonerRuns.cancel()) {
            return ResponseEntity.status(409).body(Map.of("error", "No reasoner run is in progress"));
        }
        return ResponseEntity.accepted().body(Map.of("status", "Cancel requested"));
    }

    /**
     * Opens an event stream on a reasoner run.
     *
     * <p>Every way the stream can end — the client closing it, a failed write, the timeout —
     * detaches the stream and nothing more. None of them stops the run.
     *
     * @param attach attaches the given watcher to a run, or returns empty if there is none
     */
    private ResponseEntity<SseEmitter> stream(
            Function<ReasonerRunManager.Watcher, Optional<ReasonerRunManager.Run>> attach) {
        SseEmitter emitter = new SseEmitter(reasonerStreamTimeoutMs);
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        ReasonerRunManager.Watcher watcher = new ReasonerRunManager.Watcher() {
            @Override
            public void onAttached(long startedAt, boolean started) throws IOException {
                emitter.send(SseEmitter.event().name("run").data(new ReasonerRunEvent(startedAt, started)));
            }

            @Override
            public void onQueued(List<AdminJobQueue.Summary> ahead) throws IOException {
                emitter.send(SseEmitter.event().name("queued").data(new JobEvent.Queued(ahead)));
            }

            @Override
            public void onPhase(ReasonerRunManager.Phase phase) throws IOException {
                emitter.send(SseEmitter.event()
                        .name("phase")
                        .data(new ReasonerPhaseEvent(phase.step(), phase.totalSteps(), phase.message())));
            }

            @Override
            public void onFinished(ReasonerRunManager.Outcome outcome) throws IOException {
                stopHeartbeat(heartbeat);
                emitter.send(SseEmitter.event().name("result").data(ReasonerResultsResponse.from(outcome)));
                emitter.complete();
            }
        };

        Optional<ReasonerRunManager.Run> run = attach.apply(watcher);
        if (run.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        Runnable detach = () -> {
            stopHeartbeat(heartbeat);
            reasonerRuns.detach(run.get(), watcher);
        };
        emitter.onTimeout(() -> {
            log.info("Reasoner stream timed out after {}ms — detaching; the run carries on",
                    reasonerStreamTimeoutMs);
            detach.run();
        });
        emitter.onError(throwable -> detach.run());
        emitter.onCompletion(detach);

        if (run.get().isRunning()) {
            // A closed socket is invisible to the server until it next writes, and phase events
            // are minutes apart, so without this a disconnected caller would stay attached until
            // the next phase. A comment every second turns a disconnect into a failed write.
            heartbeat.set(heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    // Spring reports a broken pipe as IllegalStateException ("Failed to send").
                    log.info("Reasoner stream heartbeat failed — client gone, detaching; the run carries on");
                    detach.run();
                }
            }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS));
        }
        return ResponseEntity.ok(emitter);
    }

    /**
     * Opens an event stream on an import, export or any watched job: {@code job}, then
     * {@code queued}, {@code started} and {@code progress} as they happen, then {@code result}.
     *
     * <p>As with the reasoner's stream, every way the stream can end — the client closing it, a
     * failed write, the timeout — detaches it and nothing more. The job carries on.
     *
     * @param attach attaches the given watcher to a job, or returns empty if there is none
     * @param result builds the {@code result} event from the job's id and how it ended
     */
    private <R> ResponseEntity<SseEmitter> jobStream(
            Function<AdminJobQueue.Watcher<R>, Optional<AdminJobQueue.Job<R>>> attach,
            BiFunction<String, AdminJobQueue.Outcome<R>, Object> result) {
        SseEmitter emitter = new SseEmitter(reasonerStreamTimeoutMs);
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        AdminJobQueue.Watcher<R> watcher = new AdminJobQueue.Watcher<>() {
            // From onAttached, which always comes first: a job that has already ended is finished
            // during attach, before attach has returned it.
            private String jobId;

            @Override
            public void onAttached(AdminJobQueue.Summary job, boolean submitted) throws IOException {
                jobId = job.id();
                emitter.send(SseEmitter.event().name("job").data(JobEvent.Attached.of(job, submitted)));
            }

            @Override
            public void onQueued(List<AdminJobQueue.Summary> ahead) throws IOException {
                emitter.send(SseEmitter.event().name("queued").data(new JobEvent.Queued(ahead)));
            }

            @Override
            public void onStarted(long startedAt) throws IOException {
                emitter.send(SseEmitter.event().name("started").data(new JobEvent.Started(startedAt)));
            }

            @Override
            public void onProgress(AdminJobQueue.Progress progress) throws IOException {
                emitter.send(SseEmitter.event().name("progress").data(progress));
            }

            @Override
            public void onFinished(AdminJobQueue.Outcome<R> outcome) throws IOException {
                stopHeartbeat(heartbeat);
                emitter.send(SseEmitter.event().name("result").data(result.apply(jobId, outcome)));
                emitter.complete();
            }
        };

        Optional<AdminJobQueue.Job<R>> job = attach.apply(watcher);
        if (job.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        Runnable detach = () -> {
            stopHeartbeat(heartbeat);
            jobs.detach(job.get(), watcher);
        };
        emitter.onTimeout(detach);
        emitter.onError(throwable -> detach.run());
        emitter.onCompletion(detach);

        if (!job.get().finished()) {
            heartbeat.set(heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    log.info("Job stream heartbeat failed — client gone, detaching; the job carries on");
                    detach.run();
                }
            }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS));
        }
        return ResponseEntity.ok(emitter);
    }

    private static void stopHeartbeat(AtomicReference<ScheduledFuture<?>> heartbeat) {
        ScheduledFuture<?> running = heartbeat.getAndSet(null);
        if (running != null) {
            running.cancel(false);
        }
    }
}
