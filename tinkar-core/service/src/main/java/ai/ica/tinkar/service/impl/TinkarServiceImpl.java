package ai.ica.tinkar.service.impl;

import ai.ica.tinkar.proto.TinkarSearchResult;
import ai.ica.tinkar.service.TinkarPrimitive;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.stamp.calculator.LatestVersionSearchResult;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.StampEntity;
import org.springframework.stereotype.Service;

import ai.ica.tinkar.dto.TinkarSearchQueryResponse;
import ai.ica.tinkar.service.TinkarService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
        List<PublicId> searchResults = null;
        try {
            searchResults = primitive.search(query, MAX_RESULTS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        List<TinkarSearchResult> results = searchResults.stream()
                .map(this::extract)
                .toList();

        // Convert proto results to DTO SearchResult format
        List<TinkarSearchQueryResponse.SearchResult> dtoResults = results.stream()
                .map(result -> new TinkarSearchQueryResponse.SearchResult(
                        result.getConceptId(),
                        result.getName(),
                        result.getDescription(),
                        null, // fullyQualifiedName - not available in proto result
                        null, // regularName - not available in proto result
                        null, // status - not available in proto result
                        null)) // lastModifiedTime - not available in proto result
                .collect(Collectors.toList());

        return TinkarSearchQueryResponse.success(query, dtoResults);
    }

    @Override
    public TinkarSearchQueryResponse conceptSearch(String query, Integer maxResults) {
        try {
            // Use provided maxResults or fall back to default
            int limit = (maxResults != null && maxResults > 0) ? maxResults : MAX_RESULTS;

            List<TinkarSearchQueryResponse.SearchResult> dtoResults = Calculators.View.Default()
                    .search(query, limit).stream()
                    .map(LatestVersionSearchResult::latestVersion)
                    .filter(Latest::isPresent)
                    .map(latestVersion -> latestVersion.get().referencedComponent().publicId())
                    .distinct()
                    .map(publicId -> {
                        int nid = EntityService.get().nidForPublicId(publicId);
                        String conceptId = publicId.asUuidList().getFirst().toString();

                        // Get different types of names/descriptions using LanguageCalculator
                        String name = PrimitiveData.textOptional(nid).orElse(null);

                        // Get fully qualified name (also used as description fallback)
                        String fullyQualifiedName = Calculators.View.Default()
                                .languageCalculator()
                                .getFullyQualifiedNameText(nid)
                                .orElse(null);

                        // Get regular description
                        String regularName = Calculators.View.Default()
                                .languageCalculator()
                                .getRegularDescriptionText(nid)
                                .orElse(null);

                        // Use FQN as description (matches what Searcher.descriptionsOf does)
                        String description = fullyQualifiedName != null ? fullyQualifiedName : "";

                        // Get status and timestamp from STAMP
                        String status = null;
                        Long lastModifiedTime = null;
                        try {
                            Entity<?> entity = EntityService.get().getEntityFast(nid);
                            if (entity != null && !entity.versions().isEmpty()) {
                                int stampNid = entity.versions().get(0).stampNid();
                                StampEntity<?> stampEntity = EntityService.get().getStampFast(stampNid);
                                if (stampEntity != null) {
                                    status = PrimitiveData.text(stampEntity.stateNid());
                                    lastModifiedTime = stampEntity.time();
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("Failed to get STAMP data for concept {}: {}", conceptId, ex.getMessage());
                        }

                        return new TinkarSearchQueryResponse.SearchResult(
                                conceptId, name, description, fullyQualifiedName, regularName, status, lastModifiedTime);
                    })
                    .toList();

            return TinkarSearchQueryResponse.success(query, dtoResults);
        } catch (Exception e) {
            return TinkarSearchQueryResponse.error(query, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getEntity(String conceptId) {
        try {
            PublicId publicId = primitive.getPublicId(conceptId);
            int nid = EntityService.get().nidForPublicId(publicId);

            // Get different types of names/descriptions using LanguageCalculator
            String name = PrimitiveData.textOptional(nid).orElse(null);

            // Get fully qualified name (also used as description fallback)
            String fullyQualifiedName = Calculators.View.Default()
                    .languageCalculator()
                    .getFullyQualifiedNameText(nid)
                    .orElse(null);

            // Get regular description
            String regularName = Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(nid)
                    .orElse(null);

            // Use FQN as description (matches what Searcher.descriptionsOf does)
            String description = fullyQualifiedName != null ? fullyQualifiedName : "";

            // Get status and timestamp from STAMP
            String status = null;
            Long lastModifiedTime = null;
            try {
                Entity<?> entity = EntityService.get().getEntityFast(nid);
                if (entity != null && !entity.versions().isEmpty()) {
                    int stampNid = entity.versions().get(0).stampNid();
                    StampEntity<?> stampEntity = EntityService.get().getStampFast(stampNid);
                    if (stampEntity != null) {
                        status = PrimitiveData.text(stampEntity.stateNid());
                        lastModifiedTime = stampEntity.time();
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to get STAMP data for concept {}: {}", conceptId, ex.getMessage());
            }

            TinkarSearchQueryResponse.SearchResult result = new TinkarSearchQueryResponse.SearchResult(
                    conceptId, name, description, fullyQualifiedName, regularName, status, lastModifiedTime);

            return TinkarSearchQueryResponse.success(conceptId, List.of(result));
        } catch (Exception e) {
            return TinkarSearchQueryResponse.error(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getChildConcepts(String conceptId) {
        try {
            PublicId parentConceptId = primitive.getPublicId(conceptId);
            List<PublicId> children = primitive.childrenOf(parentConceptId);
            List<TinkarSearchQueryResponse.SearchResult> results = children.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();
            
            return TinkarSearchQueryResponse.success(conceptId, results);
        } catch (Exception e) {
            return TinkarSearchQueryResponse.error(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getDescendantConcepts(String conceptId) {
        try {
            PublicId parentConceptId = primitive.getPublicId(conceptId);
            List<PublicId> descendants = primitive.descendantsOf(parentConceptId);
            List<TinkarSearchQueryResponse.SearchResult> results = descendants.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();
            
            return TinkarSearchQueryResponse.success(conceptId, results);
        } catch (Exception e) {
            return TinkarSearchQueryResponse.error(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getLIDRRecordConceptsFromTestKit(String conceptId) {
        try {
            PublicId testKitConceptId = primitive.getPublicId(conceptId);
            List<PublicId> lidrRecords = primitive.getLidrRecordSemanticsFromTestKit(testKitConceptId);
            List<TinkarSearchQueryResponse.SearchResult> results = lidrRecords.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();
            
            return TinkarSearchQueryResponse.success(conceptId, results);
        } catch (Exception e) {
            return TinkarSearchQueryResponse.error(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getResultConformanceConceptsFromLIDRRecord(String conceptId) {
        try {
            PublicId lidrRecordConceptId = primitive.getPublicId(conceptId);
            List<PublicId> resultConformances = primitive.getResultConformancesFromLidrRecord(lidrRecordConceptId);
            List<TinkarSearchQueryResponse.SearchResult> results = resultConformances.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();
            
            return TinkarSearchQueryResponse.success(conceptId, results);
        } catch (Exception e) {
            return TinkarSearchQueryResponse.error(conceptId, e.getMessage());
        }
    }

    @Override
    public TinkarSearchQueryResponse getAllowedResultConceptsFromResultConformance(String conceptId) {
        try {
            PublicId resultConformanceConceptId = primitive.getPublicId(conceptId);
            List<PublicId> allowedResults = primitive.getAllowedResultsFromResultConformance(resultConformanceConceptId);
            List<TinkarSearchQueryResponse.SearchResult> results = allowedResults.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();
            
            return TinkarSearchQueryResponse.success(conceptId, results);
        } catch (Exception e) {
            return TinkarSearchQueryResponse.error(conceptId, e.getMessage());
        }
    }

    private TinkarSearchResult extract(PublicId conceptId) {
        int nid = EntityService.get().nidForPublicId(conceptId);

        // Get fully qualified name (used as description)
        String description = Calculators.View.Default()
                .languageCalculator()
                .getFullyQualifiedNameText(nid)
                .orElse("");

        return TinkarSearchResult.newBuilder()
                .setConceptId(conceptId.asUuidList().getFirst().toString())
                .setDescription(description)
                .build();
    }

    @Override
    public String rebuildSearchIndex() {
        log.info("Rebuilding Lucene search index...");
        try {
            CompletableFuture<Void> future = PrimitiveData.get().recreateLuceneIndex();
            log.info("Lucene index rebuild started asynchronously");
            
            // Optionally, you can wait for completion or handle it in background
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

    private TinkarSearchQueryResponse.SearchResult publicIdToSearchResult(PublicId publicId) {
        int nid = EntityService.get().nidForPublicId(publicId);
        String conceptId = publicId.asUuidList().getFirst().toString();

        // Get different types of names/descriptions using LanguageCalculator
        String name = PrimitiveData.textOptional(nid).orElse(null);

        // Get fully qualified name (also used as description fallback)
        String fullyQualifiedName = Calculators.View.Default()
                .languageCalculator()
                .getFullyQualifiedNameText(nid)
                .orElse(null);

        // Get regular description
        String regularName = Calculators.View.Default()
                .languageCalculator()
                .getRegularDescriptionText(nid)
                .orElse(null);

        // Use FQN as description (matches what Searcher.descriptionsOf does)
        String description = fullyQualifiedName != null ? fullyQualifiedName : "";

        // Get status and timestamp from STAMP
        String status = null;
        Long lastModifiedTime = null;
        try {
            Entity<?> entity = EntityService.get().getEntityFast(nid);
            if (entity != null && !entity.versions().isEmpty()) {
                int stampNid = entity.versions().get(0).stampNid();
                StampEntity<?> stampEntity = EntityService.get().getStampFast(stampNid);
                if (stampEntity != null) {
                    status = PrimitiveData.text(stampEntity.stateNid());
                    lastModifiedTime = stampEntity.time();
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to get STAMP data for concept {}: {}", conceptId, ex.getMessage());
        }

        return new TinkarSearchQueryResponse.SearchResult(
                conceptId, name, description, fullyQualifiedName, regularName, status, lastModifiedTime);
    }
}
