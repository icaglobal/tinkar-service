package ai.ica.tinkar.service.impl;

import ai.ica.tinkar.proto.TinkarConceptDescriptions;
import ai.ica.tinkar.proto.TinkarSearchQueryResponse;
import ai.ica.tinkar.proto.TinkarSearchResult;
import ai.ica.tinkar.service.TinkarPrimitive;
import ai.ica.tinkar.service.TinkarService;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.stamp.calculator.LatestVersionSearchResult;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.schema.StampVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class TinkarServiceImpl implements TinkarService {

    private final TinkarPrimitive primitive;

    private static final int MAX_RESULTS = 100;

    public TinkarServiceImpl(TinkarPrimitive primitive) {
        this.primitive = primitive;
    }

    @Override
    public TinkarSearchQueryResponse search(String query) {
        try {
            List<PublicId> searchResults = primitive.search(query, MAX_RESULTS);
            List<TinkarSearchResult> results = searchResults.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();

            return buildSuccessResponse(query, results);
        } catch (Exception e) {
            return buildErrorResponse(query, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse conceptSearch(String query, Integer maxResults) {
        try {
            int limit = (maxResults != null && maxResults > 0) ? maxResults : MAX_RESULTS;

            List<TinkarSearchResult> results = Calculators.View.Default()
                    .search(query, limit).stream()
                    .map(LatestVersionSearchResult::latestVersion)
                    .filter(Latest::isPresent)
                    .map(latestVersion -> latestVersion.get().referencedComponent().publicId())
                    .distinct()
                    .map(this::publicIdToSearchResult)
                    .toList();

            return buildSuccessResponse(query, results);
        } catch (Exception e) {
            return buildErrorResponse(query, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getEntity(String conceptId) {
        try {
            PublicId publicId = primitive.getPublicId(conceptId);
            TinkarSearchResult result = publicIdToSearchResult(publicId);
            return buildSuccessResponse(conceptId, List.of(result));
        } catch (Exception e) {
            return buildErrorResponse(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getChildConcepts(String conceptId) {
        try {
            PublicId parentConceptId = primitive.getPublicId(conceptId);
            List<PublicId> children = primitive.childrenOf(parentConceptId);
            List<TinkarSearchResult> results = children.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();

            return buildSuccessResponse(conceptId, results);
        } catch (Exception e) {
            return buildErrorResponse(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getDescendantConcepts(String conceptId) {
        try {
            PublicId parentConceptId = primitive.getPublicId(conceptId);
            List<PublicId> descendants = primitive.descendantsOf(parentConceptId);
            List<TinkarSearchResult> results = descendants.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();

            return buildSuccessResponse(conceptId, results);
        } catch (Exception e) {
            return buildErrorResponse(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getLIDRRecordConceptsFromTestKit(String conceptId) {
        try {
            PublicId testKitConceptId = primitive.getPublicId(conceptId);
            List<PublicId> lidrRecords = primitive.getLidrRecordSemanticsFromTestKit(testKitConceptId);
            List<TinkarSearchResult> results = lidrRecords.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();

            return buildSuccessResponse(conceptId, results);
        } catch (Exception e) {
            return buildErrorResponse(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getResultConformanceConceptsFromLIDRRecord(String conceptId) {
        try {
            PublicId lidrRecordConceptId = primitive.getPublicId(conceptId);
            List<PublicId> resultConformances = primitive.getResultConformancesFromLidrRecord(lidrRecordConceptId);
            List<TinkarSearchResult> results = resultConformances.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();

            return buildSuccessResponse(conceptId, results);
        } catch (Exception e) {
            return buildErrorResponse(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getAllowedResultConceptsFromResultConformance(String conceptId) {
        try {
            PublicId resultConformanceConceptId = primitive.getPublicId(conceptId);
            List<PublicId> allowedResults = primitive.getAllowedResultsFromResultConformance(resultConformanceConceptId);
            List<TinkarSearchResult> results = allowedResults.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();

            return buildSuccessResponse(conceptId, results);
        } catch (Exception e) {
            return buildErrorResponse(conceptId, e.getMessage());
        }
    }

    @Override
    public String rebuildSearchIndex() {
        log.info("Rebuilding Lucene search index...");
        try {
            CompletableFuture<Void> future = PrimitiveData.get().recreateLuceneIndex();
            log.info("Lucene index rebuild started asynchronously");

            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("Error rebuilding Lucene index: {}", throwable.getMessage(), throwable);
                } else {
                    log.info("Lucene index rebuild completed successfully");
                }
            });

            return "Lucene search index rebuild started. This operation may take several minutes. Check logs for completion status.";
        } catch (Exception e) {
            log.error("Failed to start Lucene index rebuild: {}", e.getMessage(), e);
            return "Failed to start Lucene index rebuild: " + e.getMessage();
        }
    }

    private TinkarSearchQueryResponse buildSuccessResponse(String query, List<TinkarSearchResult> results) {
        return TinkarSearchQueryResponse.newBuilder()
                .setQuery(query != null ? query : "")
                .setTotalCount(results.size())
                .addAllResults(results)
                .setSuccess(true)
                .setErrorMessage("")
                .build();
    }

    private TinkarSearchQueryResponse buildErrorResponse(String query, String errorMessage) {
        return TinkarSearchQueryResponse.newBuilder()
                .setQuery(query != null ? query : "")
                .setTotalCount(0)
                .setSuccess(false)
                .setErrorMessage(errorMessage != null ? errorMessage : "Unknown error")
                .build();
    }

    private TinkarSearchResult publicIdToSearchResult(PublicId publicId) {
        int nid = EntityService.get().nidForPublicId(publicId);

        // Build PublicId proto
        dev.ikm.tinkar.schema.PublicId protoPublicId = dev.ikm.tinkar.schema.PublicId.newBuilder()
                .addAllUuids(publicId.asUuidList().stream()
                        .map(java.util.UUID::toString)
                        .toList())
                .build();

        // Build descriptions
        String fullyQualifiedName = Calculators.View.Default()
                .languageCalculator()
                .getFullyQualifiedNameText(nid)
                .orElse("");

        String regularName = Calculators.View.Default()
                .languageCalculator()
                .getRegularDescriptionText(nid)
                .orElse("");

        TinkarConceptDescriptions descriptions = TinkarConceptDescriptions.newBuilder()
                .setFullyQualifiedName(fullyQualifiedName)
                .setRegularName(regularName)
                .setDefinition(fullyQualifiedName)
                .build();

        // Build StampVersion
        StampVersion stamp = buildStampVersion(nid);

        return TinkarSearchResult.newBuilder()
                .setPublicId(protoPublicId)
                .setDescriptions(descriptions)
                .setStamp(stamp)
                .build();
    }

    private StampVersion buildStampVersion(int nid) {
        StampVersion.Builder stampBuilder = StampVersion.newBuilder();

        try {
            Entity<?> entity = EntityService.get().getEntityFast(nid);
            if (entity != null && !entity.versions().isEmpty()) {
                int stampNid = entity.versions().get(0).stampNid();
                StampEntity<?> stampEntity = EntityService.get().getStampFast(stampNid);
                if (stampEntity != null) {
                    setPublicIdIfPresent(stampBuilder, stampEntity.stateNid(), "status");
                    setPublicIdIfPresent(stampBuilder, stampEntity.authorNid(), "author");
                    setPublicIdIfPresent(stampBuilder, stampEntity.moduleNid(), "module");
                    setPublicIdIfPresent(stampBuilder, stampEntity.pathNid(), "path");
                    stampBuilder.setTime(stampEntity.time());
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to get STAMP data for nid {}: {}", nid, ex.getMessage());
        }

        return stampBuilder.build();
    }

    private void setPublicIdIfPresent(StampVersion.Builder stampBuilder, int nid, String fieldType) {
        try {
            Entity<?> entity = EntityService.get().getEntityFast(nid);
            if (entity != null && entity.publicId() != null) {
                dev.ikm.tinkar.schema.PublicId protoPublicId = dev.ikm.tinkar.schema.PublicId.newBuilder()
                        .addUuids(entity.publicId().asUuidList().getFirst().toString())
                        .build();

                switch (fieldType) {
                    case "status" -> stampBuilder.setStatusPublicId(protoPublicId);
                    case "author" -> stampBuilder.setAuthorPublicId(protoPublicId);
                    case "module" -> stampBuilder.setModulePublicId(protoPublicId);
                    case "path" -> stampBuilder.setPathPublicId(protoPublicId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get {} public ID for nid {}: {}", fieldType, nid, e.getMessage());
        }
    }
}
