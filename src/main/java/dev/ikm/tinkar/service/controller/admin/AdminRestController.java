package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.service.dto.ReasonerPhaseEvent;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.common.service.TrackingCallable;
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
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tier 3: Admin / Data Management — REST controller.
 *
 * Operations for importing changesets, exporting entity data,
 * and running the reasoner classification pipeline.
 * Target audience: platform operators, DevOps, CI/CD pipelines.
 */
@Slf4j
@RestController
@RequestMapping("/api/ike/admin")
@Tag(name = "IKE Admin (Tier 3)", description = "Data management operations: import changesets, export entities, and reasoner classification.")
public class AdminRestController {

    /**
     * Guards against a second classification starting while one is in flight.
     *
     * <p>The pipeline is a single stateful run over one {@code ReasonerService} instance, so two
     * concurrent runs would corrupt each other. Rejected rather than queued: a caller that is
     * told "busy" can decide what to do, whereas a queued run gives it a stream that reports
     * nothing for an unbounded time.
     */
    private final AtomicBoolean reasonerRunning = new AtomicBoolean(false);

    /** How often a running stream is probed for a disconnected client. */
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;

    /** Sends heartbeats for the running stream; one thread suffices for one run at a time. */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rest-reasoner-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Runs streaming classifications off the request thread.
     *
     * <p>An {@code SseEmitter} has to be returned before any event is written, so the work
     * cannot happen inline. Single-threaded because {@link #reasonerRunning} already admits one
     * run at a time; the queue should never hold more than the one task being executed.
     */
    private final ExecutorService reasonerExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rest-reasoner");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * How long a streaming run may take before it is cancelled, in milliseconds.
     *
     * <p>A backstop, not the way abandoned runs end — the heartbeat notices a disconnected client
     * within a second. It exists for a run that wedges (ELK starved of memory stops making
     * progress without failing), so set it well above the longest legitimate classification.
     * Configured by {@code reasoner.stream.timeout-ms}; without one, Spring's async default of
     * about 30 seconds would abort every real run.
     */
    private final long reasonerStreamTimeoutMs;

    private final TinkarService tinkarService;

    public AdminRestController(TinkarService tinkarService,
                               @Value("${reasoner.stream.timeout-ms:14400000}") long reasonerStreamTimeoutMs) {
        this.tinkarService = tinkarService;
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
            description = "Runs the full reasoner classification pipeline: " +
                    "init -> extractData -> loadData -> computeInferences -> writeInferredResults. " +
                    "This may take several minutes for large datasets.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reasoner completed",
                    content = @Content(schema = @Schema(implementation = ReasonerResultsResponse.class))),
            @ApiResponse(responseCode = "500", description = "Reasoner failed")
    })
    @PostMapping("/reasoner")
    public ResponseEntity<ReasonerResultsResponse> runReasoner() {
        return ResponseEntity.ok(tinkarService.runReasoner());
    }

    @Operation(summary = "Run the reasoner, streaming progress",
            description = "Runs the same pipeline as POST /reasoner, but returns a text/event-stream: "
                    + "one 'phase' event as each of the four phases completes, then a single 'result' "
                    + "event carrying the counts. Use this when a caller needs to show progress; use "
                    + "POST /reasoner when it only needs the outcome.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event stream opened"),
            @ApiResponse(responseCode = "409", description = "A classification is already running")
    })
    @PostMapping(value = "/reasoner/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> runReasonerStreaming() {
        if (!reasonerRunning.compareAndSet(false, true)) {
            log.info("Rejecting reasoner stream request — a classification is already running");
            return ResponseEntity.status(409).build();
        }

        SseEmitter emitter = new SseEmitter(reasonerStreamTimeoutMs);
        TrackingCallable<?> tracker = TinkarService.newCancellationTracker();

        // A client that closes the stream has stopped caring about the result, so treat it as a
        // cancellation rather than letting a classification nobody is waiting for run to the end.
        //
        // These only cancel; they do not release the guard. That happens when the worker thread
        // actually exits, in runAndStream. Releasing it here would let a new request start a
        // second pipeline beside one still running — which a wedged ELK run, deaf to interrupt,
        // otherwise makes possible.
        emitter.onTimeout(() -> {
            log.warn("Reasoner stream timed out after {}ms — cancelling the run", reasonerStreamTimeoutMs);
            tracker.cancel();
        });
        emitter.onError(throwable -> {
            log.info("Reasoner stream closed by the client — cancelling the run");
            tracker.cancel();
        });

        reasonerExecutor.execute(() -> runAndStream(emitter, tracker));
        return ResponseEntity.ok(emitter);
    }

    /**
     * Runs the pipeline, writing each phase and then the outcome to {@code emitter}.
     *
     * <p>A failed classification is sent as a {@code result} event with {@code success} false
     * rather than as a stream error, matching what the gRPC call does: the caller reads the
     * reason from the same event it would read a successful outcome from, and handles one shape
     * instead of two.
     */
    private void runAndStream(SseEmitter emitter, TrackingCallable<?> tracker) {
        // A closed socket is invisible to the server until it next writes, and phase events are
        // seconds to minutes apart — so without this a client that disconnects mid-classification
        // is only noticed after the phase it disconnected in has finished. A comment every second
        // turns a disconnect into a failed write, and so into a cancel, within a second.
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                if (!tracker.isCancelled()) {
                    log.info("Reasoner stream heartbeat failed — client gone, cancelling the run");
                    tracker.cancel();
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        try {
            streamRun(emitter, tracker);
        } finally {
            heartbeat.cancel(false);
            // The only release: the guard is held for exactly as long as a pipeline is running.
            reasonerRunning.set(false);
        }
    }

    private void streamRun(SseEmitter emitter, TrackingCallable<?> tracker) {
        long startedAt = System.currentTimeMillis();
        log.info("IkeAdmin runReasoner (streaming) started");
        try {
            var results = tinkarService.runReasoner((step, totalSteps, message) -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("phase")
                            .data(new ReasonerPhaseEvent(step, totalSteps, message)));
                } catch (IOException | IllegalStateException e) {
                    // The client has gone. Spring reports a broken pipe as IllegalStateException
                    // ("Failed to send"), not IOException, so both mean the same thing here: stop
                    // the run rather than failing it.
                    log.info("Reasoner stream send failed — cancelling the run");
                    tracker.cancel();
                }
            }, tracker);

            emitter.send(SseEmitter.event()
                    .name("result")
                    .data(ReasonerResultsResponse.from(
                            results, System.currentTimeMillis() - startedAt)));
            emitter.complete();
        } catch (CancellationException e) {
            // Expected when a client disconnects, so not logged as a failure. The stream it would
            // have been reported on is usually gone already.
            log.info("Streaming reasoner cancelled: {}", e.getMessage());
            emitter.complete();
        } catch (Exception | Error e) {
            // Error too: an OutOfMemoryError from ELK on a large dataset would otherwise kill this
            // thread without completing the stream, leaving the caller waiting forever and the
            // concurrency guard held until the stream times out.
            log.error("Streaming reasoner failed: {}", e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event()
                        .name("result")
                        .data(ReasonerResultsResponse.error(
                                e.getMessage() == null ? e.toString() : e.getMessage())));
                emitter.complete();
            } catch (IOException sendFailure) {
                // Nothing left to report through — the stream is already gone.
                emitter.completeWithError(sendFailure);
            }
        }
    }
}
