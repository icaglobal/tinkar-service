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
import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.tinkar.service.service.TinkarService;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    public AdminGrpcController(TinkarService tinkarService) {
        this.tinkarService = tinkarService;
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
        long startedAt = System.currentTimeMillis();

        TrackingCallable<?> tracker = TinkarService.newCancellationTracker();
        // A client that cancels the call — Komet's cancel button, or a dropped connection — has
        // stopped waiting for the result, so stop the run rather than classifying for nobody.
        //
        // Through the call's Context, not setOnCancelHandler: the pipeline runs on this call's own
        // thread, and gRPC delivers a call's callbacks one at a time — so the cancel handler would
        // be queued behind this method and fire only after the classification had finished. The
        // Context is cancelled on the transport thread as soon as the client resets the stream.
        // gRPC also cancels the Context when a call ends normally, so only a cancel that lands
        // while the run is still going means the client left.
        AtomicBoolean finished = new AtomicBoolean(false);
        Context.current().addListener(cancelled -> {
            if (!finished.get()) {
                log.info("Reasoner call cancelled by the client — cancelling the run");
                tracker.cancel();
            }
        }, Runnable::run);

        try {
            ClassifierResults results = tinkarService.runReasoner(
                    (step, totalSteps, message) -> responseObserver.onNext(
                            RunReasonerEvent.newBuilder()
                                    .setPhase(ReasonerPhase.newBuilder()
                                            .setStep(step)
                                            .setTotalSteps(totalSteps)
                                            .setMessage(message)
                                            .build())
                                    .build()), tracker);

            finished.set(true);
            responseObserver.onNext(RunReasonerEvent.newBuilder()
                    .setResult(toResult(results, System.currentTimeMillis() - startedAt))
                    .build());
        } catch (CancellationException e) {
            // The client asked for this, and its stream is already gone — nothing to report back
            // to, and nothing wrong to report.
            finished.set(true);
            log.info("Reasoner call cancelled: {}", e.getMessage());
            return;
        } catch (Exception | Error e) {
            // Error too: an OutOfMemoryError would otherwise end the call without a result, so
            // the client waits forever instead of hearing that the run failed.
            finished.set(true);
            log.error("Reasoner failed: {}", e.getMessage(), e);
            // Reported as a result with success=false rather than onError, so the caller reads
            // the reason from the same message it would read a successful outcome from.
            responseObserver.onNext(RunReasonerEvent.newBuilder()
                    .setResult(RunReasonerResult.newBuilder()
                            .setSuccess(false)
                            .setErrorMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                            .setDurationMs(System.currentTimeMillis() - startedAt)
                            .setCreatedAt(System.currentTimeMillis())
                            .build())
                    .build());
        }
        responseObserver.onCompleted();
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
