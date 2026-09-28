package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
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
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    public AdminRestController(TinkarService tinkarService,
                               ReasonerRunManager reasonerRuns,
                               @Value("${reasoner.stream.timeout-ms:14400000}") long reasonerStreamTimeoutMs) {
        this.tinkarService = tinkarService;
        this.reasonerRuns = reasonerRuns;
        this.reasonerStreamTimeoutMs = reasonerStreamTimeoutMs;
    }

    @Operation(summary = "Import a changeset",
            description = "Imports a Tinkar changeset from a protobuf ZIP file. " +
                    "Uses multi-pass import by default to handle forward references.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(schema = @Schema(implementation = EntityCountSummaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid file or parameters")
    })
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EntityCountSummaryResponse> importChangeset(
            @Parameter(description = "Protobuf ZIP file to import", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Use multi-pass import to resolve forward references (default: true)")
            @RequestParam(required = false, defaultValue = "true") boolean useMultiPass) {

        File tempFile = null;
        try {
            tempFile = Files.createTempFile("tinkar-import-", ".zip").toFile();
            file.transferTo(tempFile);

            EntityCountSummaryResponse result = tinkarService.importChangeset(tempFile, useMultiPass);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(EntityCountSummaryResponse.error(e.getMessage()));
        } finally {
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    log.warn("Failed to delete temp file: {}", tempFile.getAbsolutePath());
                }
            }
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

    private static void stopHeartbeat(AtomicReference<ScheduledFuture<?>> heartbeat) {
        ScheduledFuture<?> running = heartbeat.getAndSet(null);
        if (running != null) {
            running.cancel(false);
        }
    }
}
