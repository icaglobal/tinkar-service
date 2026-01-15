package ai.ica.tinkar.controller;

import ai.ica.tinkar.dto.TinkarSearchQueryResponse;
import ai.ica.tinkar.proto.*;
import ai.ica.tinkar.service.TinkarService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

@GrpcService
@Slf4j
public class TinkarSearchGrpcController extends TinkarSearchServiceGrpc.TinkarSearchServiceImplBase {

    private final TinkarService tinkarService;

    public TinkarSearchGrpcController(TinkarService tinkarService) {
        this.tinkarService = tinkarService;
    }

    @Override
    public void search(TinkarSearchQueryRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC search request for query: {}", request.getQuery());
        TinkarSearchQueryResponse response = tinkarService.search(request.getQuery());
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void conceptSearch(TinkarConceptSearchRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC conceptSearch request for query: {} with maxResults: {}",
                request.getQuery(), request.getMaxResults());

        // Convert maxResults, treating 0 or negative as null (use server default)
        Integer maxResults = request.getMaxResults() > 0 ? request.getMaxResults() : null;

        TinkarSearchQueryResponse response = tinkarService.conceptSearch(request.getQuery(), maxResults);
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getEntity(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC getEntity request for conceptId: {}", request.getConceptId());
        TinkarSearchQueryResponse response = tinkarService.getEntity(request.getConceptId());
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getChildConcepts(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC getChildConcepts request for conceptId: {}", request.getConceptId());
        TinkarSearchQueryResponse response = tinkarService.getChildConcepts(request.getConceptId());
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getDescendantConcepts(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC getDescendantConcepts request for conceptId: {}", request.getConceptId());
        TinkarSearchQueryResponse response = tinkarService.getDescendantConcepts(request.getConceptId());
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getLIDRRecordConceptsFromTestKit(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC getLIDRRecordConceptsFromTestKit request for conceptId: {}", request.getConceptId());
        TinkarSearchQueryResponse response = tinkarService.getLIDRRecordConceptsFromTestKit(request.getConceptId());
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getResultConformanceConceptsFromLIDRRecord(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC getResultConformanceConceptsFromLIDRRecord request for conceptId: {}", request.getConceptId());
        TinkarSearchQueryResponse response = tinkarService
                .getResultConformanceConceptsFromLIDRRecord(request.getConceptId());
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getAllowedResultConceptsFromResultConformance(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        log.info("gRPC getAllowedResultConceptsFromResultConformance request for conceptId: {}",
                request.getConceptId());
        TinkarSearchQueryResponse response = tinkarService
                .getAllowedResultConceptsFromResultConformance(request.getConceptId());
        responseObserver.onNext(mapToProto(response));
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

    private ai.ica.tinkar.proto.TinkarSearchQueryResponse mapToProto(TinkarSearchQueryResponse dto) {
        ai.ica.tinkar.proto.TinkarSearchQueryResponse.Builder responseBuilder = ai.ica.tinkar.proto.TinkarSearchQueryResponse
                .newBuilder()
                .setQuery(dto.query() != null ? dto.query() : "")
                .setTotalCount(dto.totalCount() != null ? dto.totalCount() : 0L)
                .setSuccess(dto.success() != null ? dto.success() : false)
                .setErrorMessage(dto.errorMessage() != null ? dto.errorMessage() : "");

        if (dto.results() != null) {
            responseBuilder.addAllResults(dto.results().stream()
                    .map(result -> {
                        TinkarSearchResult.Builder resultBuilder = TinkarSearchResult.newBuilder()
                                .setConceptId(result.conceptId() != null ? result.conceptId() : "")
                                .setName(result.name() != null ? result.name() : "")
                                .setDescription(result.description() != null ? result.description() : "");

                        // Add new fields if present
                        if (result.fullyQualifiedName() != null) {
                            resultBuilder.setFullyQualifiedName(result.fullyQualifiedName());
                        }
                        if (result.regularName() != null) {
                            resultBuilder.setRegularName(result.regularName());
                        }
                        if (result.status() != null) {
                            resultBuilder.setStatus(result.status());
                        }
                        if (result.lastModifiedTime() != null) {
                            resultBuilder.setLastModifiedTime(result.lastModifiedTime());
                        }

                        return resultBuilder.build();
                    })
                    .collect(Collectors.toList()));
        }

        return responseBuilder.build();
    }
}
