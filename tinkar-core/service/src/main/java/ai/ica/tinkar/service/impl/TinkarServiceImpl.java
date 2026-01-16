package ai.ica.tinkar.service.impl;

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
import ai.ica.tinkar.dto.TinkarSearchQueryResponse.Descriptions;
import ai.ica.tinkar.dto.TinkarSearchQueryResponse.Stamp;
import ai.ica.tinkar.dto.TinkarSearchQueryResponse.SearchResult;
import ai.ica.tinkar.service.TinkarService;
import lombok.extern.slf4j.Slf4j;

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
        List<PublicId> searchResults = null;
        try {
            searchResults = primitive.search(query, MAX_RESULTS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        List<SearchResult> dtoResults = searchResults.stream()
                .map(this::publicIdToSearchResult)
                .toList();

        return TinkarSearchQueryResponse.success(query, dtoResults);
    }

    @Override
    public TinkarSearchQueryResponse conceptSearch(String query, Integer maxResults) {
        try {
            // Use provided maxResults or fall back to default
            int limit = (maxResults != null && maxResults > 0) ? maxResults : MAX_RESULTS;

            List<SearchResult> dtoResults = Calculators.View.Default()
                    .search(query, limit).stream()
                    .map(LatestVersionSearchResult::latestVersion)
                    .filter(Latest::isPresent)
                    .map(latestVersion -> latestVersion.get().referencedComponent().publicId())
                    .distinct()
                    .map(this::publicIdToSearchResult)
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
            SearchResult result = publicIdToSearchResult(publicId);
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
            List<SearchResult> results = children.stream()
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
            List<SearchResult> results = descendants.stream()
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
            List<SearchResult> results = lidrRecords.stream()
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
            List<SearchResult> results = resultConformances.stream()
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
            List<SearchResult> results = allowedResults.stream()
                    .map(this::publicIdToSearchResult)
                    .toList();

            return TinkarSearchQueryResponse.success(conceptId, results);
        } catch (Exception e) {
            return TinkarSearchQueryResponse.error(conceptId, e.getMessage());
        }
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

    private SearchResult publicIdToSearchResult(PublicId publicId) {
        int nid = EntityService.get().nidForPublicId(publicId);

        // Convert PublicId to list of UUID strings
        List<String> uuids = publicId.asUuidList().stream()
                .map(java.util.UUID::toString)
                .toList();

        // Get descriptions using LanguageCalculator
        String fullyQualifiedName = Calculators.View.Default()
                .languageCalculator()
                .getFullyQualifiedNameText(nid)
                .orElse(null);

        String regularName = Calculators.View.Default()
                .languageCalculator()
                .getRegularDescriptionText(nid)
                .orElse(null);

        // Use FQN as definition fallback
        String definition = fullyQualifiedName != null ? fullyQualifiedName : "";

        Descriptions descriptions = new Descriptions(fullyQualifiedName, regularName, definition);

        // Get STAMP info
        Stamp stamp = null;
        try {
            Entity<?> entity = EntityService.get().getEntityFast(nid);
            if (entity != null && !entity.versions().isEmpty()) {
                int stampNid = entity.versions().get(0).stampNid();
                StampEntity<?> stampEntity = EntityService.get().getStampFast(stampNid);
                if (stampEntity != null) {
                    String statusPublicId = getPublicIdString(stampEntity.stateNid());
                    String authorPublicId = getPublicIdString(stampEntity.authorNid());
                    String modulePublicId = getPublicIdString(stampEntity.moduleNid());
                    String pathPublicId = getPublicIdString(stampEntity.pathNid());
                    Long time = stampEntity.time();

                    stamp = new Stamp(statusPublicId, authorPublicId, modulePublicId, pathPublicId, time);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to get STAMP data for concept {}: {}", uuids.get(0), ex.getMessage());
        }

        return new SearchResult(uuids, descriptions, stamp);
    }

    private String getPublicIdString(int nid) {
        try {
            Entity<?> entity = EntityService.get().getEntityFast(nid);
            if (entity != null && entity.publicId() != null) {
                return entity.publicId().asUuidList().getFirst().toString();
            }
        } catch (Exception e) {
            log.warn("Failed to get public ID for nid {}: {}", nid, e.getMessage());
        }
        return null;
    }
}
