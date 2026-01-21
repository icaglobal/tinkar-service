package ai.ica.tinkar.controller;

import ai.ica.tinkar.proto.*;
import ai.ica.tinkar.service.TinkarService;
import dev.ikm.tinkar.schema.PublicId;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@Slf4j
public class TinkarSearchGrpcController extends TinkarSearchServiceGrpc.TinkarSearchServiceImplBase {

    private final TinkarService tinkarService;

    public TinkarSearchGrpcController(TinkarService tinkarService) {
        this.tinkarService = tinkarService;
    }

    @Override
    public void search(TinkarSearchQueryRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC search request for query: {}", request.getQuery());
        responseObserver.onNext(tinkarService.search(request.getQuery()));
        responseObserver.onCompleted();
    }

    @Override
    public void conceptSearch(TinkarConceptSearchRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC conceptSearch request for query: {} with maxResults: {}",
                request.getQuery(), request.getMaxResults());
        Integer maxResults = request.getMaxResults() > 0 ? request.getMaxResults() : null;
        responseObserver.onNext(tinkarService.conceptSearch(request.getQuery(), maxResults));
        responseObserver.onCompleted();
    }

    @Override
    public void getEntity(TinkarConceptIdRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getEntity request for conceptId: {}", conceptId);
        responseObserver.onNext(tinkarService.getEntity(conceptId));
        responseObserver.onCompleted();
    }

    @Override
    public void getChildConcepts(TinkarConceptIdRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getChildConcepts request for conceptId: {}", conceptId);
        responseObserver.onNext(tinkarService.getChildConcepts(conceptId));
        responseObserver.onCompleted();
    }

    @Override
    public void getDescendantConcepts(TinkarConceptIdRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getDescendantConcepts request for conceptId: {}", conceptId);
        responseObserver.onNext(tinkarService.getDescendantConcepts(conceptId));
        responseObserver.onCompleted();
    }

    @Override
    public void getLIDRRecordConceptsFromTestKit(TinkarConceptIdRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getLIDRRecordConceptsFromTestKit request for conceptId: {}", conceptId);
        responseObserver.onNext(tinkarService.getLIDRRecordConceptsFromTestKit(conceptId));
        responseObserver.onCompleted();
    }

    @Override
    public void getResultConformanceConceptsFromLIDRRecord(TinkarConceptIdRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getResultConformanceConceptsFromLIDRRecord request for conceptId: {}", conceptId);
        responseObserver.onNext(tinkarService.getResultConformanceConceptsFromLIDRRecord(conceptId));
        responseObserver.onCompleted();
    }

    @Override
    public void getAllowedResultConceptsFromResultConformance(TinkarConceptIdRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getAllowedResultConceptsFromResultConformance request for conceptId: {}", conceptId);
        responseObserver.onNext(tinkarService.getAllowedResultConceptsFromResultConformance(conceptId));
        responseObserver.onCompleted();
    }

    @Override
    public void rebuildSearchIndex(TinkarRebuildIndexRequest request,
            StreamObserver<TinkarRebuildIndexResponse> responseObserver) {
        log.info("gRPC rebuildSearchIndex request");
        String message = tinkarService.rebuildSearchIndex();
        boolean success = !message.startsWith("Failed");
        TinkarRebuildIndexResponse response = TinkarRebuildIndexResponse.newBuilder()
                .setMessage(message)
                .setSuccess(success)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private String extractConceptId(PublicId publicId) {
        if (publicId == null || publicId.getUuidsList().isEmpty()) {
            return "";
        }
        return publicId.getUuids(0);
    }
}
