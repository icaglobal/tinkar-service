package ai.ica.tinkar.controller;

import ai.ica.tinkar.dto.ConceptSearchResponse;
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
    public void conceptSearchWithSort(TinkarConceptSearchWithSortRequest request,
            StreamObserver<TinkarConceptSearchWithSortResponse> responseObserver) {
        log.info("gRPC conceptSearchWithSort request for query: {} with maxResults: {} and sortBy: {}",
                request.getQuery(), request.getMaxResults(), request.getSortBy());
        Integer maxResults = request.getMaxResults() > 0 ? request.getMaxResults() : null;
        ai.ica.tinkar.dto.SearchSortOption sortOption = convertSortOption(request.getSortBy());
        ConceptSearchResponse dtoResponse = tinkarService.conceptSearchWithSort(
                request.getQuery(), maxResults, sortOption);
        responseObserver.onNext(convertToGrpcResponse(dtoResponse));
        responseObserver.onCompleted();
    }

    private ai.ica.tinkar.dto.SearchSortOption convertSortOption(SearchSortOption grpcSortOption) {
        if (grpcSortOption == null) {
            return ai.ica.tinkar.dto.SearchSortOption.TOP_COMPONENT;
        }
        return switch (grpcSortOption) {
            case TOP_COMPONENT -> ai.ica.tinkar.dto.SearchSortOption.TOP_COMPONENT;
            case TOP_COMPONENT_ALPHA -> ai.ica.tinkar.dto.SearchSortOption.TOP_COMPONENT_ALPHA;
            case SEMANTIC -> ai.ica.tinkar.dto.SearchSortOption.SEMANTIC;
            case SEMANTIC_ALPHA -> ai.ica.tinkar.dto.SearchSortOption.SEMANTIC_ALPHA;
            default -> ai.ica.tinkar.dto.SearchSortOption.TOP_COMPONENT;
        };
    }

    private SearchSortOption convertToGrpcSortOption(ai.ica.tinkar.dto.SearchSortOption dtoSortOption) {
        if (dtoSortOption == null) {
            return SearchSortOption.TOP_COMPONENT;
        }
        return switch (dtoSortOption) {
            case TOP_COMPONENT -> SearchSortOption.TOP_COMPONENT;
            case TOP_COMPONENT_ALPHA -> SearchSortOption.TOP_COMPONENT_ALPHA;
            case SEMANTIC -> SearchSortOption.SEMANTIC;
            case SEMANTIC_ALPHA -> SearchSortOption.SEMANTIC_ALPHA;
        };
    }

    private TinkarConceptSearchWithSortResponse convertToGrpcResponse(ConceptSearchResponse dtoResponse) {
        TinkarConceptSearchWithSortResponse.Builder builder = TinkarConceptSearchWithSortResponse.newBuilder()
                .setQuery(dtoResponse.query() != null ? dtoResponse.query() : "")
                .setTotalCount(dtoResponse.totalCount() != null ? dtoResponse.totalCount() : 0L)
                .setSortBy(convertToGrpcSortOption(dtoResponse.sortBy()))
                .setSuccess(dtoResponse.success() != null && dtoResponse.success());

        if (dtoResponse.errorMessage() != null) {
            builder.setErrorMessage(dtoResponse.errorMessage());
        }

        // Add flat results (SEMANTIC modes)
        if (dtoResponse.results() != null) {
            for (ConceptSearchResponse.SemanticSearchResult result : dtoResponse.results()) {
                TinkarSemanticSearchResult.Builder resultBuilder = TinkarSemanticSearchResult.newBuilder()
                        .setFullyQualifiedName(result.fullyQualifiedName() != null ? result.fullyQualifiedName() : "")
                        .setScore(result.score() != null ? result.score() : 0f)
                        .setActive(result.active() != null && result.active());

                if (result.publicId() != null) {
                    resultBuilder.addAllPublicId(result.publicId());
                }
                if (result.regularName() != null) {
                    resultBuilder.setRegularName(result.regularName());
                }
                if (result.highlightedText() != null) {
                    resultBuilder.setHighlightedText(result.highlightedText());
                }

                builder.addResults(resultBuilder.build());
            }
        }

        // Add grouped results (TOP_COMPONENT modes)
        if (dtoResponse.groupedResults() != null) {
            for (ConceptSearchResponse.GroupedSearchResult group : dtoResponse.groupedResults()) {
                TinkarGroupedSearchResult.Builder groupBuilder = TinkarGroupedSearchResult.newBuilder()
                        .setFullyQualifiedName(group.fullyQualifiedName() != null ? group.fullyQualifiedName() : "")
                        .setTopScore(group.topScore() != null ? group.topScore() : 0f)
                        .setActive(group.active() != null && group.active());

                if (group.publicId() != null) {
                    groupBuilder.addAllPublicId(group.publicId());
                }

                if (group.matchingSemantics() != null) {
                    for (ConceptSearchResponse.MatchingSemantic semantic : group.matchingSemantics()) {
                        TinkarMatchingSemantic.Builder semanticBuilder = TinkarMatchingSemantic.newBuilder()
                                .setScore(semantic.score() != null ? semantic.score() : 0f);

                        if (semantic.highlightedText() != null) {
                            semanticBuilder.setHighlightedText(semantic.highlightedText());
                        }
                        if (semantic.plainText() != null) {
                            semanticBuilder.setPlainText(semantic.plainText());
                        }

                        groupBuilder.addMatchingSemantics(semanticBuilder.build());
                    }
                }

                builder.addGroupedResults(groupBuilder.build());
            }
        }

        return builder.build();
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

    @Override
    public void getConceptSemantics(TinkarConceptIdRequest request,
            StreamObserver<TinkarConceptSemanticsResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("gRPC getConceptSemantics request for conceptId: {}", conceptId);
        responseObserver.onNext(tinkarService.getConceptSemanticsProto(conceptId));
        responseObserver.onCompleted();
    }

    private String extractConceptId(PublicId publicId) {
        if (publicId == null || publicId.getUuidsList().isEmpty()) {
            return "";
        }
        return publicId.getUuids(0);
    }
}
