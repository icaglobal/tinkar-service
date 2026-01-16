package ai.ica.tinkar.controller;

import ai.ica.tinkar.dto.TinkarSearchQueryResponse;
import ai.ica.tinkar.proto.*;
import ai.ica.tinkar.service.TinkarService;
import dev.ikm.tinkar.schema.PublicId;
import dev.ikm.tinkar.schema.StampVersion;
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
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getEntity request for conceptId: {}", conceptId);
        TinkarSearchQueryResponse response = tinkarService.getEntity(conceptId);
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getChildConcepts(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getChildConcepts request for conceptId: {}", conceptId);
        TinkarSearchQueryResponse response = tinkarService.getChildConcepts(conceptId);
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getDescendantConcepts(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getDescendantConcepts request for conceptId: {}", conceptId);
        TinkarSearchQueryResponse response = tinkarService.getDescendantConcepts(conceptId);
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getLIDRRecordConceptsFromTestKit(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getLIDRRecordConceptsFromTestKit request for conceptId: {}", conceptId);
        TinkarSearchQueryResponse response = tinkarService.getLIDRRecordConceptsFromTestKit(conceptId);
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getResultConformanceConceptsFromLIDRRecord(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getResultConformanceConceptsFromLIDRRecord request for conceptId: {}", conceptId);
        TinkarSearchQueryResponse response = tinkarService
                .getResultConformanceConceptsFromLIDRRecord(conceptId);
        responseObserver.onNext(mapToProto(response));
        responseObserver.onCompleted();
    }

    @Override
    public void getAllowedResultConceptsFromResultConformance(TinkarConceptIdRequest request,
            StreamObserver<ai.ica.tinkar.proto.TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getAllowedResultConceptsFromResultConformance request for conceptId: {}",
                conceptId);
        TinkarSearchQueryResponse response = tinkarService
                .getAllowedResultConceptsFromResultConformance(conceptId);
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

    /**
     * Extracts the first UUID from a PublicId as the concept ID string.
     */
    private String extractConceptId(PublicId publicId) {
        if (publicId == null || publicId.getUuidsList().isEmpty()) {
            return "";
        }
        return publicId.getUuids(0);
    }

    /**
     * Converts a list of UUID strings to a PublicId proto message.
     */
    private PublicId toPublicId(java.util.List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return PublicId.getDefaultInstance();
        }
        return PublicId.newBuilder().addAllUuids(uuids).build();
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
                    .map(this::mapSearchResultToProto)
                    .collect(Collectors.toList()));
        }

        return responseBuilder.build();
    }

    private TinkarSearchResult mapSearchResultToProto(TinkarSearchQueryResponse.SearchResult result) {
        TinkarSearchResult.Builder resultBuilder = TinkarSearchResult.newBuilder();

        // Set PublicId
        if (result.publicId() != null) {
            resultBuilder.setPublicId(toPublicId(result.publicId()));
        }

        // Set descriptions
        if (result.descriptions() != null) {
            TinkarConceptDescriptions.Builder descriptionsBuilder = TinkarConceptDescriptions.newBuilder();
            if (result.descriptions().fullyQualifiedName() != null) {
                descriptionsBuilder.setFullyQualifiedName(result.descriptions().fullyQualifiedName());
            }
            if (result.descriptions().regularName() != null) {
                descriptionsBuilder.setRegularName(result.descriptions().regularName());
            }
            if (result.descriptions().definition() != null) {
                descriptionsBuilder.setDefinition(result.descriptions().definition());
            }
            resultBuilder.setDescriptions(descriptionsBuilder.build());
        }

        // Set StampVersion
        if (result.stamp() != null) {
            StampVersion.Builder stampBuilder = StampVersion.newBuilder();
            if (result.stamp().statusPublicId() != null) {
                stampBuilder.setStatusPublicId(toPublicId(java.util.List.of(result.stamp().statusPublicId())));
            }
            if (result.stamp().authorPublicId() != null) {
                stampBuilder.setAuthorPublicId(toPublicId(java.util.List.of(result.stamp().authorPublicId())));
            }
            if (result.stamp().modulePublicId() != null) {
                stampBuilder.setModulePublicId(toPublicId(java.util.List.of(result.stamp().modulePublicId())));
            }
            if (result.stamp().pathPublicId() != null) {
                stampBuilder.setPathPublicId(toPublicId(java.util.List.of(result.stamp().pathPublicId())));
            }
            if (result.stamp().time() != null) {
                stampBuilder.setTime(result.stamp().time());
            }
            resultBuilder.setStamp(stampBuilder.build());
        }

        return resultBuilder.build();
    }
}
