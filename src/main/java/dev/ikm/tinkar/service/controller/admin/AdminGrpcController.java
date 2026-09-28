package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.service.proto.EntityCountSummaryProto;
import dev.ikm.tinkar.service.proto.ExportEntitiesRequest;
import dev.ikm.tinkar.service.proto.ExportEntitiesResponse;
import dev.ikm.tinkar.service.proto.IkeAdminGrpc;
import dev.ikm.tinkar.service.proto.ImportChangesetRequest;
import dev.ikm.tinkar.service.proto.ImportChangesetResponse;
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
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.io.File;
import java.io.FileOutputStream;
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
@Slf4j
public class AdminGrpcController extends IkeAdminGrpc.IkeAdminImplBase {

    private final TinkarService tinkarService;

    private final ReasonerRunManager reasonerRuns;

    public AdminGrpcController(TinkarService tinkarService, ReasonerRunManager reasonerRuns) {
        this.tinkarService = tinkarService;
        this.reasonerRuns = reasonerRuns;
    }

    @Override
    public void importChangeset(ImportChangesetRequest request,
            StreamObserver<ImportChangesetResponse> responseObserver) {
        log.info("IkeAdmin importChangeset request ({} bytes, multiPass={})",
                request.getChangesetData().size(), request.getUseMultiPass());

        File tempFile = null;
        try {
            // Write uploaded bytes to temp file
            tempFile = Files.createTempFile("tinkar-import-", ".zip").toFile();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                request.getChangesetData().writeTo(fos);
            }

            // Proto3 bools default to false; callers should set use_multi_pass=true explicitly
            EntityCountSummaryResponse result = tinkarService.importChangeset(tempFile, request.getUseMultiPass());

            ImportChangesetResponse.Builder builder = ImportChangesetResponse.newBuilder()
                    .setSuccess(result.success())
                    .setErrorMessage(result.errorMessage() != null ? result.errorMessage() : "");

            if (result.success()) {
                builder.setEntityCounts(EntityCountSummaryProto.newBuilder()
                        .setConceptsCount(result.conceptsCount())
                        .setSemanticsCount(result.semanticsCount())
                        .setPatternsCount(result.patternsCount())
                        .setStampsCount(result.stampsCount())
                        .setTotalCount(result.totalCount())
                        .build());
            }

            builder.setCreatedAt(System.currentTimeMillis());
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to process import request: {}", e.getMessage(), e);
            responseObserver.onNext(ImportChangesetResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .setCreatedAt(System.currentTimeMillis())
                    .build());
            responseObserver.onCompleted();
        } finally {
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    log.warn("Failed to delete temp file: {}", tempFile.getAbsolutePath());
                }
            }
        }
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
    private static dev.ikm.tinkar.schema.PublicId publicIdOf(int nid) {
        return dev.ikm.tinkar.schema.PublicId.newBuilder()
                .addAllUuids(PrimitiveData.publicId(nid).asUuidList().stream()
                        .map(java.util.UUID::toString)
                        .toList())
                .build();
    }
}
