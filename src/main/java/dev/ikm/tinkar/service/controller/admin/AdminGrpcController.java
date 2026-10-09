package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.entity.changeset.SchemaIds;
import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.service.proto.ExportEntitiesRequest;
import dev.ikm.tinkar.service.proto.IkeAdminGrpc;
import dev.ikm.tinkar.service.proto.ImportChangesetChunk;
import dev.ikm.tinkar.service.proto.JobEvent;
import dev.ikm.tinkar.service.proto.WatchJobRequest;
import dev.ikm.tinkar.service.proto.CancelJobRequest;
import dev.ikm.tinkar.service.proto.CancelJobResponse;
import dev.ikm.tinkar.service.dto.ExportRequest;
import dev.ikm.tinkar.service.service.AdminJobQueue;
import dev.ikm.tinkar.service.service.ChangesetJobService;
import dev.ikm.tinkar.service.proto.RunReasonerEvent;
import dev.ikm.tinkar.service.proto.RunReasonerResult;
import dev.ikm.tinkar.service.proto.ReasonerPhase;
import dev.ikm.tinkar.service.proto.EquivalentSet;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import dev.ikm.tinkar.coordinate.view.ViewCoordinateRecord;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.service.proto.ReasonerResultsProto;
import dev.ikm.tinkar.service.proto.RunReasonerRequest;
import dev.ikm.tinkar.service.proto.ReasonerRunInfo;
import dev.ikm.tinkar.service.proto.WatchReasonerRequest;
import dev.ikm.tinkar.service.proto.CancelReasonerRequest;
import dev.ikm.tinkar.service.proto.CancelReasonerResponse;
import dev.ikm.tinkar.service.service.ReasonerRunManager;
import dev.ikm.tinkar.service.service.TinkarService;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.devh.boot.grpc.server.service.GrpcService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.List;

/**
 * Tier 3: Admin / Data Management — gRPC controller.
 *
 * Operations for importing changesets, exporting entity data,
 * and running the reasoner classification pipeline.
 * Target audience: platform operators, DevOps, CI/CD pipelines.
 */
@GrpcService
public class AdminGrpcController extends IkeAdminGrpc.IkeAdminImplBase {
    private static final Logger log = LoggerFactory.getLogger(AdminGrpcController.class);

    private final TinkarService tinkarService;

    private final ReasonerRunManager reasonerRuns;

    private final AdminJobQueue jobs;

    private final ChangesetJobService changesets;

    public AdminGrpcController(TinkarService tinkarService, ReasonerRunManager reasonerRuns,
                               AdminJobQueue jobs, ChangesetJobService changesets) {
        this.tinkarService = tinkarService;
        this.reasonerRuns = reasonerRuns;
        this.jobs = jobs;
        this.changesets = changesets;
    }

    @Override
    public StreamObserver<ImportChangesetChunk> importChangeset(StreamObserver<JobEvent> responseObserver) {
        // A call with a streaming request may only set its cancel handler now, before this method
        // returns — not later when the upload completes and the job exists. So register it here, and
        // have followJob fill in what it should do.
        AtomicReference<Runnable> onClientGone = new AtomicReference<>(() -> { });
        if (responseObserver instanceof ServerCallStreamObserver<JobEvent> call) {
            call.setOnCancelHandler(() -> onClientGone.get().run());
        }
        return new StreamObserver<>() {
            private File upload;
            private OutputStream out;
            private String name = "upload.zip";
            private boolean singlePass;

            @Override
            public void onNext(ImportChangesetChunk chunk) {
                try {
                    switch (chunk.getPartCase()) {
                        case START -> {
                            name = chunk.getStart().getFileName().isBlank() ? name : chunk.getStart().getFileName();
                            singlePass = chunk.getStart().getSinglePass();
                            upload = Files.createTempFile("tinkar-import-", ".zip").toFile();
                            out = new BufferedOutputStream(new FileOutputStream(upload));
                        }
                        case DATA -> {
                            if (out == null) {
                                throw new IllegalStateException("Send an ImportChangesetStart before any data");
                            }
                            chunk.getData().writeTo(out);
                        }
                        case PART_NOT_SET -> throw new IllegalStateException("Empty ImportChangesetChunk");
                    }
                } catch (IOException | IllegalStateException e) {
                    discard();
                    responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
                }
            }

            @Override
            public void onError(Throwable t) {
                // The client went away mid-upload: nothing was queued, so nothing to keep.
                log.info("Changeset upload abandoned: {}", t.toString());
                discard();
            }

            @Override
            public void onCompleted() {
                if (out == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("No changeset was sent").asRuntimeException());
                    return;
                }
                try {
                    out.close();
                } catch (IOException e) {
                    discard();
                    responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                    return;
                }
                log.info("IkeAdmin importChangeset: {} ({} bytes)", name, upload.length());
                File queued = upload;
                upload = null;
                out = null;
                followJob(responseObserver,
                        watcher -> changesets.importChangeset(queued, name, !singlePass, watcher),
                        JobEvents::importResult, false, onClientGone);
            }

            private void discard() {
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (IOException ignored) {
                    // being discarded anyway
                }
                if (upload != null && !upload.delete()) {
                    log.warn("Could not delete {}", upload);
                }
                out = null;
                upload = null;
            }
        };
    }

    @Override
    public void exportEntities(ExportEntitiesRequest request, StreamObserver<JobEvent> responseObserver) {
        ExportRequest export;
        try {
            export = toExportRequest(request);
            export.validate();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        log.info("IkeAdmin exportEntities: {}", export.describe());
        followJob(responseObserver, watcher -> changesets.export(export, watcher), JobEvents::exportResult, true, null);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void watchJob(WatchJobRequest request, StreamObserver<JobEvent> responseObserver) {
        Optional<AdminJobQueue.Job<?>> found = jobs.find(request.getJobId());
        if (found.isEmpty() || found.get().kind() == AdminJobQueue.Kind.REASONER) {
            // Reasoner runs have their own stream, WatchReasoner, with the results Komet's panel reads.
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No import or export job " + request.getJobId() + " — it may have expired")
                    .asRuntimeException());
            return;
        }
        AdminJobQueue.Job job = found.get();
        java.util.function.BiFunction<String, AdminJobQueue.Outcome, JobEvent> result =
                job.kind() == AdminJobQueue.Kind.IMPORT
                        ? (id, outcome) -> JobEvents.importResult(id, outcome)
                        : (id, outcome) -> JobEvents.exportResult(id, outcome);
        followJob(responseObserver, watcher -> {
            jobs.watch(job, watcher);
            return job;
        }, (java.util.function.BiFunction) result, false, null);
    }

    @Override
    public void cancelJob(CancelJobRequest request, StreamObserver<CancelJobResponse> responseObserver) {
        Optional<AdminJobQueue.Job<?>> found = jobs.find(request.getJobId());
        if (found.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No job " + request.getJobId() + " — it may have expired").asRuntimeException());
            return;
        }
        try {
            if (!jobs.cancel(found.get())) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Job " + request.getJobId() + " has already finished").asRuntimeException());
                return;
            }
        } catch (UnsupportedOperationException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        responseObserver.onNext(CancelJobResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /** Size of each file_chunk: well under gRPC's default 4 MiB message limit. */
    private static final int FILE_CHUNK_BYTES = 1024 * 1024;

    /**
     * Streams a job to {@code responseObserver}: job, queued, started, progress, then the result.
     *
     * <p>Events are sent from the job's thread, so this returns as soon as the watcher is attached.
     * A call that ends early is detached and nothing more — the job carries on.
     *
     * <p>With {@code sendFile}, a successful export's ZIP is sent as file_chunk events before the
     * result. That happens on a separate thread: the job queue runs one job at a time, and sending
     * a large file from its thread would hold up every job behind this one.
     */
    private <R> void followJob(StreamObserver<JobEvent> responseObserver,
                               Function<AdminJobQueue.Watcher<R>, AdminJobQueue.Job<R>> attach,
                               java.util.function.BiFunction<String, AdminJobQueue.Outcome<R>, JobEvent> result,
                               boolean sendFile, AtomicReference<Runnable> preRegisteredCancelHook) {
        AtomicReference<AdminJobQueue.Job<R>> attached = new AtomicReference<>();
        AdminJobQueue.Watcher<R> watcher = new AdminJobQueue.Watcher<>() {
            private String jobId;

            @Override
            public void onAttached(AdminJobQueue.Summary job, boolean submitted) {
                jobId = job.id();
                responseObserver.onNext(JobEvents.job(job));
            }

            @Override
            public void onQueued(List<AdminJobQueue.Summary> ahead) {
                responseObserver.onNext(JobEvents.queued(ahead));
            }

            @Override
            public void onStarted(long startedAt) {
                responseObserver.onNext(JobEvents.started(startedAt));
            }

            @Override
            public void onProgress(AdminJobQueue.Progress progress) {
                responseObserver.onNext(JobEvents.progress(progress));
            }

            @Override
            public void onFinished(AdminJobQueue.Outcome<R> outcome) {
                if (sendFile && outcome.result() instanceof ChangesetJobService.ExportResult export) {
                    fileSender.execute(() -> sendFileThenResult(responseObserver, export.file(),
                            result.apply(jobId, outcome)));
                    return;
                }
                responseObserver.onNext(result.apply(jobId, outcome));
                responseObserver.onCompleted();
            }
        };
        Runnable detach = () -> {
            AdminJobQueue.Job<R> job = attached.get();
            if (job != null && !job.finished()) {
                log.info("Job call ended by the client — detaching; the job carries on");
                jobs.detach(job, watcher);
            }
        };
        if (preRegisteredCancelHook != null) {
            preRegisteredCancelHook.set(detach);
        } else if (responseObserver instanceof ServerCallStreamObserver<JobEvent> call) {
            call.setOnCancelHandler(detach);
        }
        attached.set(attach.apply(watcher));
    }

    private void sendFileThenResult(StreamObserver<JobEvent> responseObserver, File file, JobEvent result) {
        ServerCallStreamObserver<JobEvent> call = responseObserver instanceof ServerCallStreamObserver<JobEvent> c ? c : null;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[FILE_CHUNK_BYTES];
            int read;
            while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                if (call != null) {
                    if (call.isCancelled()) {
                        return; // the client left; the file stays downloadable over REST for an hour
                    }
                    // Simple flow control: do not queue the whole file in memory for a slow client.
                    while (!call.isReady() && !call.isCancelled()) {
                        Thread.sleep(5);
                    }
                }
                responseObserver.onNext(JobEvent.newBuilder().setFileChunk(ByteString.copyFrom(buffer, 0, read)).build());
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (IOException e) {
            responseObserver.onError(Status.INTERNAL.withDescription("Could not send the export: " + e.getMessage())
                    .asRuntimeException());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.info("Export file not sent — client gone ({})", e.toString());
        }
    }

    /** Sends finished exports' files, off the job queue's thread. */
    private final java.util.concurrent.ExecutorService fileSender =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private static ExportRequest toExportRequest(ExportEntitiesRequest request) {
        return switch (request.getExportType()) {
            case FULL -> new ExportRequest(ExportRequest.ExportType.FULL, null, null, null);
            case TEMPORAL -> new ExportRequest(ExportRequest.ExportType.TEMPORAL,
                    request.getFromEpochMillis(), request.getToEpochMillis(), null);
            case MEMBERSHIP -> new ExportRequest(ExportRequest.ExportType.MEMBERSHIP, null, null,
                    request.getMembershipTagsList().stream()
                            .map(tag -> tag.getUuidsCount() == 0 ? "" : tag.getUuids(0))
                            .filter(uuid -> !uuid.isBlank())
                            .toList());
            case UNRECOGNIZED -> throw new IllegalArgumentException("Unknown export type");
        };
    }

    @Override
    public void runReasoner(RunReasonerRequest request,
            StreamObserver<RunReasonerEvent> responseObserver) {
        log.info("IkeAdmin runReasoner request");
        follow(responseObserver, watcher -> Optional.of(reasonerRuns.runOrAttach(watcher).run()));
    }

    @Override
    public void watchReasoner(WatchReasonerRequest request,
            StreamObserver<RunReasonerEvent> responseObserver) {
        log.info("IkeAdmin watchReasoner request");
        if (!follow(responseObserver, reasonerRuns::watch)) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No reasoner run since the server started")
                    .asRuntimeException());
        }
    }

    @Override
    public void cancelReasoner(CancelReasonerRequest request,
            StreamObserver<CancelReasonerResponse> responseObserver) {
        log.info("IkeAdmin cancelReasoner request");
        if (!reasonerRuns.cancel()) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("No reasoner run is in progress")
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(CancelReasonerResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Streams a reasoner run to {@code responseObserver}: the run, its phases, then the result.
     *
     * <p>Returns as soon as the watcher is attached; events are sent from the run's own thread.
     * The call thread is not held for the length of a classification, which also means gRPC can
     * deliver the call's cancel as soon as the client sends it.
     *
     * <p>A call that ends early — the client cancelling it, a dropped connection, Komet closing —
     * is detached and nothing more. It does not stop the run; {@code CancelReasoner} does.
     *
     * @param attach attaches the given watcher to a run, or returns empty if there is none
     * @return false if there was no run to follow, in which case nothing was sent
     */
    private boolean follow(StreamObserver<RunReasonerEvent> responseObserver,
            Function<ReasonerRunManager.Watcher, Optional<ReasonerRunManager.Run>> attach) {
        AtomicReference<ReasonerRunManager.Run> attached = new AtomicReference<>();
        ReasonerRunManager.Watcher watcher = new ReasonerRunManager.Watcher() {
            @Override
            public void onAttached(long startedAt, boolean started) {
                responseObserver.onNext(RunReasonerEvent.newBuilder()
                        .setRun(ReasonerRunInfo.newBuilder()
                                .setStartedAt(startedAt)
                                .setStarted(started)
                                .build())
                        .build());
            }

            @Override
            public void onPhase(ReasonerRunManager.Phase phase) {
                responseObserver.onNext(RunReasonerEvent.newBuilder()
                        .setPhase(ReasonerPhase.newBuilder()
                                .setStep(phase.step())
                                .setTotalSteps(phase.totalSteps())
                                .setMessage(phase.message())
                                .build())
                        .build());
            }

            @Override
            public void onFinished(ReasonerRunManager.Outcome outcome) {
                responseObserver.onNext(RunReasonerEvent.newBuilder()
                        .setResult(toResult(outcome))
                        .build());
                responseObserver.onCompleted();
            }
        };

        if (responseObserver instanceof ServerCallStreamObserver<RunReasonerEvent> call) {
            call.setOnCancelHandler(() -> {
                ReasonerRunManager.Run run = attached.get();
                if (run != null && run.isRunning()) {
                    log.info("Reasoner call ended by the client — detaching; the run carries on");
                    reasonerRuns.detach(run, watcher);
                }
            });
        }

        Optional<ReasonerRunManager.Run> run = attach.apply(watcher);
        run.ifPresent(attached::set);
        return run.isPresent();
    }

    /** Maps how a run ended onto the wire type, whichever way it ended. */
    private RunReasonerResult toResult(ReasonerRunManager.Outcome outcome) {
        return switch (outcome.state()) {
            case SUCCEEDED -> toResult(outcome.results(), outcome.durationMs());
            case CANCELLED -> RunReasonerResult.newBuilder()
                    .setSuccess(false)
                    .setCancelled(true)
                    .setErrorMessage("")
                    .setDurationMs(outcome.durationMs())
                    .setCreatedAt(System.currentTimeMillis())
                    .build();
            case FAILED -> RunReasonerResult.newBuilder()
                    // Reported as a result with success=false rather than onError, so the caller
                    // reads the reason from the same message it would read a success from.
                    .setSuccess(false)
                    .setErrorMessage(outcome.errorMessage())
                    .setDurationMs(outcome.durationMs())
                    .setCreatedAt(System.currentTimeMillis())
                    .build();
            case RUNNING -> throw new IllegalArgumentException("A running run has no outcome yet");
        };
    }

    /** Maps the reasoner's results onto the wire type. */
    private RunReasonerResult toResult(ClassifierResults results, long durationMs) {
        RunReasonerResult.Builder builder = RunReasonerResult.newBuilder()
                .setSuccess(true)
                .setErrorMessage("")
                .setDurationMs(durationMs)
                .setCreatedAt(System.currentTimeMillis());

        builder.setCounts(ReasonerResultsProto.newBuilder()
                .setClassifiedConceptCount(results.getClassificationConceptSet().size())
                .setInferredChangesCount(results.getConceptsWithInferredChanges().size())
                .setNavigationChangesCount(results.getConceptsWithNavigationChanges().size())
                .setEquivalentSetsCount(results.getEquivalentSets().size())
                .setCyclesCount(results.getCycles() != null ? results.getCycles().size() : 0)
                .setOrphansCount(results.getOrphans() != null ? results.getOrphans().size() : 0)
                .build());

        // classificationConceptSet is intentionally not sent — see the proto. Its size is
        // carried in counts above, which is all the panel reads.
        results.getConceptsWithInferredChanges()
                .forEach(nid -> builder.addConceptsWithInferredChanges(publicIdOf(nid)));
        results.getConceptsWithNavigationChanges()
                .forEach(nid -> builder.addConceptsWithNavigationChanges(publicIdOf(nid)));
        if (results.getOrphans() != null) {
            results.getOrphans().forEach(nid -> builder.addOrphans(publicIdOf(nid)));
        }
        results.getEquivalentSets().forEach(set -> {
            EquivalentSet.Builder equivalent = EquivalentSet.newBuilder();
            set.forEach(nid -> equivalent.addConcept(publicIdOf(nid)));
            builder.addEquivalentSets(equivalent.build());
        });

        ViewCoordinateRecord viewCoordinate = results.getViewCoordinate();
        if (viewCoordinate != null) {
            // Sent as text because the results panel only ever displays these; the commit time
            // is the one part read functionally, to advance the caller's view far enough to see
            // what was just written.
            builder.setCommitTime(viewCoordinate.stampCoordinate().stampPosition().time());
            builder.setStampCoordinateText(viewCoordinate.stampCoordinate().toUserString());
            builder.setLogicCoordinateText(viewCoordinate.logicCoordinate().toUserString());
            builder.setEditCoordinateText(viewCoordinate.editCoordinate().toUserString());
        }
        return builder.build();
    }

    /**
     * Nids are assigned per data store, so they are meaningless to a caller. Every concept
     * crosses the wire as its PublicId, which the caller resolves against its own store.
     */
    private static dev.ikm.tinkar.schema.PublicId publicIdOf(long nid) {
        return SchemaIds.toSchema(PrimitiveData.publicId(nid));
    }
}
