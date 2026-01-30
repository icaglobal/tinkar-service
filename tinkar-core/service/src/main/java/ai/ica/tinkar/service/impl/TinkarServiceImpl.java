package ai.ica.tinkar.service.impl;

import ai.ica.tinkar.dto.ChangeHistoryResponse;
import ai.ica.tinkar.dto.ChangeHistoryResponse.FieldChange;
import ai.ica.tinkar.dto.ChangeHistoryResponse.StampInfo;
import ai.ica.tinkar.dto.ChangeHistoryResponse.VersionChange;
import ai.ica.tinkar.dto.ConceptChangeHistoryResponse;
import ai.ica.tinkar.dto.ConceptSearchResponse;
import ai.ica.tinkar.dto.ConceptSearchResponse.GroupedSearchResult;
import ai.ica.tinkar.dto.ConceptSearchResponse.MatchingSemantic;
import ai.ica.tinkar.dto.ConceptSearchResponse.SemanticSearchResult;
import ai.ica.tinkar.dto.ConceptSemanticsResponse;
import ai.ica.tinkar.dto.ConceptSemanticsResponse.SemanticInfo;
import ai.ica.tinkar.dto.ConceptSemanticsResponse.FieldValue;
import ai.ica.tinkar.dto.DescendantOperationResponse;
import ai.ica.tinkar.dto.SearchSortOption;
import dev.ikm.tinkar.common.id.IntIdSet;
import dev.ikm.tinkar.common.id.IntIds;
import ai.ica.tinkar.proto.TinkarConceptDescriptions;
import ai.ica.tinkar.proto.TinkarSearchQueryResponse;
import ai.ica.tinkar.proto.TinkarSearchResult;
import ai.ica.tinkar.service.TinkarPrimitive;
import ai.ica.tinkar.service.TinkarService;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.stamp.calculator.LatestVersionSearchResult;
import dev.ikm.tinkar.coordinate.stamp.change.ChangeChronology;
import dev.ikm.tinkar.coordinate.stamp.change.FieldChangeRecord;
import dev.ikm.tinkar.coordinate.stamp.change.VersionChangeRecord;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.RecordListBuilder;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.schema.StampVersion;
import dev.ikm.tinkar.terms.TinkarTerm;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.factory.primitive.IntSets;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
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
    public ConceptSearchResponse conceptSearchWithSort(String query, Integer maxResults, SearchSortOption sortBy) {
        try {
            int limit = (maxResults != null && maxResults > 0) ? maxResults : MAX_RESULTS;
            SearchSortOption effectiveSortBy = (sortBy != null) ? sortBy : SearchSortOption.TOP_COMPONENT;

            // Execute the search
            List<LatestVersionSearchResult> searchResults = Calculators.View.Default()
                    .search(query, limit)
                    .toList();

            log.debug("Search for '{}' returned {} raw results", query, searchResults.size());

            return switch (effectiveSortBy) {
                case TOP_COMPONENT -> buildGroupedResponse(query, searchResults, effectiveSortBy, false);
                case TOP_COMPONENT_ALPHA -> buildGroupedResponse(query, searchResults, effectiveSortBy, true);
                case SEMANTIC -> buildFlatResponse(query, searchResults, effectiveSortBy, false);
                case SEMANTIC_ALPHA -> buildFlatResponse(query, searchResults, effectiveSortBy, true);
            };

        } catch (Exception e) {
            log.error("Error in conceptSearchWithSort for query '{}': {}", query, e.getMessage(), e);
            return ConceptSearchResponse.error(query, e.getMessage());
        }
    }

    /**
     * Builds a flat (semantic) response, sorted by score or alphabetically.
     */
    private ConceptSearchResponse buildFlatResponse(String query, List<LatestVersionSearchResult> results,
            SearchSortOption sortBy, boolean alphabetical) {

        // Sort the results
        List<LatestVersionSearchResult> sortedResults = new ArrayList<>(results);
        if (alphabetical) {
            // Sort alphabetically by matched text
            sortedResults.sort((r1, r2) -> {
                String text1 = getPlainText(r1);
                String text2 = getPlainText(r2);
                return compareStringsNaturally(text1, text2);
            });
        } else {
            // Sort by score (highest first)
            sortedResults.sort((r1, r2) -> Float.compare(r2.score(), r1.score()));
        }

        // Convert to response format
        List<SemanticSearchResult> semanticResults = sortedResults.stream()
                .filter(r -> r.latestVersion().isPresent())
                .map(this::toSemanticSearchResult)
                .toList();

        return ConceptSearchResponse.successFlat(query, sortBy, semanticResults);
    }

    /**
     * Builds a grouped (top component) response, sorted by score or alphabetically.
     */
    private ConceptSearchResponse buildGroupedResponse(String query, List<LatestVersionSearchResult> results,
            SearchSortOption sortBy, boolean alphabetical) {

        // Group results by top-level component (concept)
        Map<Integer, List<LatestVersionSearchResult>> groupedByTopNid;

        if (alphabetical) {
            // Use TreeMap for alphabetical ordering of concepts
            groupedByTopNid = new TreeMap<>((nid1, nid2) -> {
                String name1 = getConceptName(nid1);
                String name2 = getConceptName(nid2);
                return compareStringsNaturally(name1, name2);
            });
        } else {
            // Use LinkedHashMap to preserve insertion order (by score)
            groupedByTopNid = new LinkedHashMap<>();
        }

        // Sort initial results by score for proper grouping
        List<LatestVersionSearchResult> sortedForGrouping = new ArrayList<>(results);
        sortedForGrouping.sort((r1, r2) -> Float.compare(r2.score(), r1.score()));

        // Group by top component nid
        for (LatestVersionSearchResult result : sortedForGrouping) {
            if (result.latestVersion().isPresent()) {
                int topNid = result.latestVersion().get().chronology().topEnclosingComponentNid();
                groupedByTopNid.computeIfAbsent(topNid, k -> new ArrayList<>()).add(result);
            }
        }

        // Convert to response format
        List<GroupedSearchResult> groupedResults = new ArrayList<>();
        long totalSemanticCount = 0;

        for (Map.Entry<Integer, List<LatestVersionSearchResult>> entry : groupedByTopNid.entrySet()) {
            int topNid = entry.getKey();
            List<LatestVersionSearchResult> conceptResults = entry.getValue();

            // Sort matches within this concept
            if (alphabetical) {
                conceptResults.sort((r1, r2) -> compareStringsNaturally(getPlainText(r1), getPlainText(r2)));
            } else {
                conceptResults.sort((r1, r2) -> Float.compare(r2.score(), r1.score()));
            }

            // Get concept info
            PublicId conceptPublicId = PrimitiveData.publicId(topNid);
            String fqn = getConceptName(topNid);
            boolean active = isConceptActive(topNid);
            float topScore = conceptResults.isEmpty() ? 0f : conceptResults.get(0).score();

            // Build matching semantics list
            List<MatchingSemantic> matchingSemantics = conceptResults.stream()
                    .map(r -> new MatchingSemantic(
                            r.highlightedString(),
                            getPlainText(r),
                            r.score()))
                    .toList();

            groupedResults.add(new GroupedSearchResult(
                    conceptPublicId.asUuidList().stream().map(UUID::toString).toList(),
                    fqn,
                    active,
                    topScore,
                    matchingSemantics));

            totalSemanticCount += matchingSemantics.size();
        }

        // If not alphabetical, sort grouped results by their top score
        if (!alphabetical) {
            groupedResults.sort((g1, g2) -> Float.compare(g2.topScore(), g1.topScore()));
        }

        return ConceptSearchResponse.successGrouped(query, sortBy, groupedResults, totalSemanticCount);
    }

    /**
     * Converts a LatestVersionSearchResult to a SemanticSearchResult.
     */
    private SemanticSearchResult toSemanticSearchResult(LatestVersionSearchResult result) {
        var latestVersion = result.latestVersion().get();
        int topNid = latestVersion.chronology().topEnclosingComponentNid();

        PublicId conceptPublicId = PrimitiveData.publicId(topNid);
        String fqn = getConceptName(topNid);
        String regularName = getConceptRegularName(topNid);
        boolean active = isConceptActive(topNid);

        return new SemanticSearchResult(
                conceptPublicId.asUuidList().stream().map(UUID::toString).toList(),
                fqn,
                regularName,
                result.highlightedString(),
                result.score(),
                active);
    }

    /**
     * Gets the plain text from a search result by stripping HTML tags.
     */
    private String getPlainText(LatestVersionSearchResult result) {
        String highlighted = result.highlightedString();
        if (highlighted == null) {
            return "";
        }
        // Remove HTML bold tags and normalize whitespace
        return highlighted
                .replaceAll("<B>", "")
                .replaceAll("</B>", "")
                .replaceAll("<b>", "")
                .replaceAll("</b>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Gets the fully qualified name for a concept by nid.
     */
    private String getConceptName(int nid) {
        return Calculators.View.Default()
                .languageCalculator()
                .getFullyQualifiedDescriptionTextWithFallbackOrNid(nid);
    }

    /**
     * Gets the regular/preferred name for a concept by nid.
     */
    private String getConceptRegularName(int nid) {
        return Calculators.View.Default()
                .languageCalculator()
                .getRegularDescriptionText(nid)
                .orElse(null);
    }

    /**
     * Checks if a concept is active.
     */
    private boolean isConceptActive(int nid) {
        var latest = Calculators.View.Default().latest(nid);
        return latest.isPresent() && latest.get().active();
    }

    /**
     * Natural order string comparison that handles numbers intelligently.
     * e.g., "Item 2" comes before "Item 10".
     */
    private int compareStringsNaturally(String s1, String s2) {
        if (s1 == null && s2 == null) return 0;
        if (s1 == null) return -1;
        if (s2 == null) return 1;
        return s1.compareToIgnoreCase(s2);
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

            // Debug: Log navigation semantics for this parent
            int parentNid = EntityService.get().nidForPublicId(parentConceptId);
            log.debug("Getting descendants for parent {} (nid: {})", conceptId, parentNid);

            int[] parentNavSemantics = PrimitiveData.get().semanticNidsForComponentOfPattern(
                parentNid, TinkarTerm.INFERRED_NAVIGATION_PATTERN.nid());
            log.debug("Parent has {} INFERRED navigation semantics attached", parentNavSemantics.length);

            // Debug: Examine the first navigation semantic to understand the structure
            if (parentNavSemantics.length > 0) {
                Entity<?> firstSemantic = EntityService.get().getEntityFast(parentNavSemantics[0]);
                if (firstSemantic instanceof SemanticEntity<?> semantic) {
                    log.debug("Example navigation semantic structure:");
                    log.debug("  - Attached to component nid: {}", semantic.referencedComponentNid());
                    log.debug("  - Pattern nid: {}", semantic.patternNid());
                    if (!semantic.versions().isEmpty()) {
                        SemanticEntityVersion version = semantic.versions().get(semantic.versions().size() - 1);
                        log.debug("  - Field 0 (DESTINATION): {}", version.fieldValues().get(0));
                        log.debug("  - Field 1 (ORIGIN): {}", version.fieldValues().get(1));
                    }
                }
            }

            List<PublicId> descendants = primitive.descendantsOf(parentConceptId);
            log.debug("NavigationCalculator returned {} descendants", descendants.size());

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

    @Override
    public ChangeHistoryResponse getChangeHistory(String entityId) {
        try {
            PublicId publicId = primitive.getPublicId(entityId);
            int nid = EntityService.get().nidForPublicId(publicId);

            // Get the entity description
            String entityDescription = Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(nid)
                    .orElse("Unknown entity");

            // Get change chronology using the stamp calculator
            ChangeChronology changeChronology = Calculators.View.Default()
                    .stampCalculator()
                    .changeChronology(nid);

            // Convert to DTO
            List<VersionChange> versionChanges = convertChangeChronologyToDto(changeChronology);

            return ChangeHistoryResponse.success(entityId, entityDescription, versionChanges);
        } catch (Exception e) {
            log.error("Failed to get change history for entity {}: {}", entityId, e.getMessage(), e);
            return ChangeHistoryResponse.error(entityId, e.getMessage());
        }
    }

    @Override
    public ChangeHistoryResponse createSampleChange(String conceptId, String comment) {
        try {
            PublicId conceptPublicId = primitive.getPublicId(conceptId);
            int conceptNid = EntityService.get().nidForPublicId(conceptPublicId);

            // Get the entity description for context
            String conceptDescription = Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(conceptNid)
                    .orElse("Unknown concept");

            // Create a new semantic (comment) attached to the concept
            UUID semanticUuid = UUID.randomUUID();
            long currentTime = System.currentTimeMillis();

            // Create a transaction for the change
            Transaction transaction = Transaction.make("Add comment to " + conceptDescription);

            try {
                // Get STAMP for this transaction (Active state, current time, user, module, path)
                StampEntity<?> stamp = transaction.getStamp(
                        dev.ikm.tinkar.terms.State.ACTIVE,
                        currentTime,
                        TinkarTerm.USER.nid(),
                        TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                        TinkarTerm.DEVELOPMENT_PATH.nid()
                );

                // Build the semantic record with the comment pattern
                // Comment pattern has one field: the comment text
                SemanticRecord semanticRecord = SemanticRecord.build(
                        semanticUuid,
                        TinkarTerm.COMMENT_PATTERN.nid(),
                        conceptNid,
                        stamp.versions().get(0),
                        Lists.immutable.of(comment)
                );

                // Persist the semantic record to the entity store
                EntityService.get().putEntity(semanticRecord);

                // Add to transaction and commit
                transaction.addComponent(semanticRecord);
                transaction.commit();

                // Note: Changes are held in memory until saveChanges() is called.
                // This allows for a review process before persisting to disk.

                log.info("Created comment semantic {} on concept {} with comment: {}",
                        semanticUuid, conceptId, comment);

                // Build the response directly from the data we just created
                // (The newly created semantic may not be immediately queryable)
                return buildChangeResponseForNewSemantic(
                        semanticUuid.toString(),
                        conceptDescription,
                        comment,
                        stamp,
                        currentTime
                );

            } catch (Exception e) {
                transaction.cancel();
                throw e;
            }

        } catch (Exception e) {
            log.error("Failed to create sample change for concept {}: {}", conceptId, e.getMessage(), e);
            return ChangeHistoryResponse.error(conceptId, e.getMessage());
        }
    }

    private ChangeHistoryResponse buildChangeResponseForNewSemantic(
            String semanticId,
            String conceptDescription,
            String comment,
            StampEntity<?> stamp,
            long time) {

        // Build STAMP info from the stamp entity
        String status = getDescriptionForNid(stamp.stateNid());
        String author = getDescriptionForNid(stamp.authorNid());
        String module = getDescriptionForNid(stamp.moduleNid());
        String path = getDescriptionForNid(stamp.pathNid());
        String formattedTime = formatTimestamp(time);

        StampInfo stampInfo = new StampInfo(status, author, module, path, time, formattedTime);

        // Build field change for the comment field (newly added)
        String patternName = getDescriptionForNid(TinkarTerm.COMMENT_PATTERN.nid());
        FieldChange commentFieldChange = new FieldChange(
                patternName + " [0]",
                0,
                null,  // No prior value - this is a new semantic
                comment,
                "ADDED"
        );

        VersionChange versionChange = new VersionChange(stampInfo, List.of(commentFieldChange));

        String entityDescription = "Comment on: " + conceptDescription;

        return new ChangeHistoryResponse(
                semanticId,
                entityDescription,
                1,
                List.of(versionChange),
                true,
                null
        );
    }

    private List<VersionChange> convertChangeChronologyToDto(ChangeChronology changeChronology) {
        List<VersionChange> versionChanges = new ArrayList<>();

        for (VersionChangeRecord versionChange : changeChronology.changeRecords()) {
            StampInfo stampInfo = buildStampInfo(versionChange.stampNid());
            List<FieldChange> fieldChanges = convertFieldChanges(versionChange.changes());
            versionChanges.add(new VersionChange(stampInfo, fieldChanges));
        }

        return versionChanges;
    }

    private StampInfo buildStampInfo(int stampNid) {
        try {
            StampEntity<?> stampEntity = EntityService.get().getStampFast(stampNid);
            if (stampEntity == null) {
                return new StampInfo(null, null, null, null, null, null);
            }

            String status = getDescriptionForNid(stampEntity.stateNid());
            String author = getDescriptionForNid(stampEntity.authorNid());
            String module = getDescriptionForNid(stampEntity.moduleNid());
            String path = getDescriptionForNid(stampEntity.pathNid());
            long time = stampEntity.time();
            String formattedTime = formatTimestamp(time);

            return new StampInfo(status, author, module, path, time, formattedTime);
        } catch (Exception e) {
            log.warn("Failed to build STAMP info for stampNid {}: {}", stampNid, e.getMessage());
            return new StampInfo(null, null, null, null, null, null);
        }
    }

    private String getDescriptionForNid(int nid) {
        try {
            return Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(nid)
                    .orElse("nid: " + nid);
        } catch (Exception e) {
            return "nid: " + nid;
        }
    }

    private String formatTimestamp(long epochMillis) {
        if (epochMillis == Long.MIN_VALUE || epochMillis == Long.MAX_VALUE) {
            return "N/A";
        }
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    private List<FieldChange> convertFieldChanges(Iterable<FieldChangeRecord> fieldChangeRecords) {
        List<FieldChange> fieldChanges = new ArrayList<>();

        for (FieldChangeRecord fieldChange : fieldChangeRecords) {
            String fieldName = determineFieldName(fieldChange);
            Integer fieldIndex = fieldChange.currentValue() != null
                    ? fieldChange.currentValue().indexInPattern()
                    : (fieldChange.priorValue() != null ? fieldChange.priorValue().indexInPattern() : null);

            String priorValue = fieldChange.priorValue() != null
                    ? formatFieldValue(fieldChange.priorValue().value())
                    : null;
            String currentValue = fieldChange.currentValue() != null
                    ? formatFieldValue(fieldChange.currentValue().value())
                    : null;

            String changeType = determineChangeType(priorValue, currentValue);

            fieldChanges.add(new FieldChange(fieldName, fieldIndex, priorValue, currentValue, changeType));
        }

        return fieldChanges;
    }

    private String determineFieldName(FieldChangeRecord fieldChange) {
        // Try to get a meaningful name from the pattern
        int patternNid = fieldChange.currentValue() != null
                ? fieldChange.currentValue().patternNid()
                : (fieldChange.priorValue() != null ? fieldChange.priorValue().patternNid() : 0);

        if (patternNid != 0) {
            try {
                String patternName = Calculators.View.Default()
                        .languageCalculator()
                        .getRegularDescriptionText(patternNid)
                        .orElse(null);
                if (patternName != null) {
                    int index = fieldChange.currentValue() != null
                            ? fieldChange.currentValue().indexInPattern()
                            : fieldChange.priorValue().indexInPattern();
                    return patternName + " [" + index + "]";
                }
            } catch (Exception e) {
                // Fall through to default
            }
        }
        return "Field";
    }

    private String formatFieldValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof PublicId publicId) {
            // Try to get a description for the public ID
            try {
                int nid = EntityService.get().nidForPublicId(publicId);
                return Calculators.View.Default()
                        .languageCalculator()
                        .getRegularDescriptionText(nid)
                        .orElse(publicId.toString());
            } catch (Exception e) {
                return publicId.toString();
            }
        }
        return value.toString();
    }

    private String determineChangeType(String priorValue, String currentValue) {
        if (priorValue == null && currentValue != null) {
            return "ADDED";
        } else if (priorValue != null && currentValue == null) {
            return "REMOVED";
        } else {
            return "MODIFIED";
        }
    }

    @Override
    public ConceptSemanticsResponse getConceptComments(String conceptId) {
        try {
            PublicId publicId = primitive.getPublicId(conceptId);
            int conceptNid = EntityService.get().nidForPublicId(publicId);

            // Get the concept description
            String conceptDescription = Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(conceptNid)
                    .orElse("Unknown concept");

            // Get all comment semantics for this concept using the Comment Pattern
            int[] semanticNids = EntityService.get().semanticNidsForComponentOfPattern(
                    conceptNid, TinkarTerm.COMMENT_PATTERN.nid());

            List<SemanticInfo> semantics = new ArrayList<>();
            for (int semanticNid : semanticNids) {
                SemanticInfo semanticInfo = buildSemanticInfo(semanticNid);
                if (semanticInfo != null) {
                    semantics.add(semanticInfo);
                }
            }

            return ConceptSemanticsResponse.success(conceptId, conceptDescription, semantics);
        } catch (Exception e) {
            log.error("Failed to get comments for concept {}: {}", conceptId, e.getMessage(), e);
            return ConceptSemanticsResponse.error(conceptId, e.getMessage());
        }
    }

    @Override
    public ConceptSemanticsResponse getConceptSemantics(String conceptId) {
        try {
            PublicId publicId = primitive.getPublicId(conceptId);
            int conceptNid = EntityService.get().nidForPublicId(publicId);

            // Get the concept description
            String conceptDescription = Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(conceptNid)
                    .orElse("Unknown concept");

            // Get all semantics for this concept (any pattern)
            int[] semanticNids = EntityService.get().semanticNidsForComponent(conceptNid);

            List<SemanticInfo> semantics = new ArrayList<>();
            for (int semanticNid : semanticNids) {
                SemanticInfo semanticInfo = buildSemanticInfo(semanticNid);
                if (semanticInfo != null) {
                    semantics.add(semanticInfo);
                }
            }

            return ConceptSemanticsResponse.success(conceptId, conceptDescription, semantics);
        } catch (Exception e) {
            log.error("Failed to get semantics for concept {}: {}", conceptId, e.getMessage(), e);
            return ConceptSemanticsResponse.error(conceptId, e.getMessage());
        }
    }

    private SemanticInfo buildSemanticInfo(int semanticNid) {
        try {
            Entity<?> entity = EntityService.get().getEntityFast(semanticNid);
            if (!(entity instanceof SemanticEntity<?> semanticEntity)) {
                return null;
            }

            // Get the semantic's public ID
            String semanticId = semanticEntity.publicId().asUuidList().get(0).toString();

            // Get the pattern name
            String patternName = getDescriptionForNid(semanticEntity.patternNid());

            // Get the latest version to extract fields and stamp
            if (semanticEntity.versions().isEmpty()) {
                return null;
            }

            SemanticEntityVersion latestVersion = semanticEntity.versions().get(
                    semanticEntity.versions().size() - 1);

            // Build field values
            List<FieldValue> fields = new ArrayList<>();
            Object[] fieldValues = latestVersion.fieldValues().toArray();
            for (int i = 0; i < fieldValues.length; i++) {
                String value = formatFieldValue(fieldValues[i]);
                fields.add(new FieldValue(i, value));
            }

            // Build stamp info
            ConceptSemanticsResponse.StampInfo stampInfo = buildSemanticStampInfo(latestVersion.stampNid());

            return new SemanticInfo(semanticId, patternName, fields, stampInfo);
        } catch (Exception e) {
            log.warn("Failed to build semantic info for nid {}: {}", semanticNid, e.getMessage());
            return null;
        }
    }

    private ConceptSemanticsResponse.StampInfo buildSemanticStampInfo(int stampNid) {
        try {
            StampEntity<?> stampEntity = EntityService.get().getStampFast(stampNid);
            if (stampEntity == null) {
                return new ConceptSemanticsResponse.StampInfo(null, null, null, null, null, null);
            }

            String status = getDescriptionForNid(stampEntity.stateNid());
            String author = getDescriptionForNid(stampEntity.authorNid());
            String module = getDescriptionForNid(stampEntity.moduleNid());
            String path = getDescriptionForNid(stampEntity.pathNid());
            long time = stampEntity.time();
            String formattedTime = formatTimestamp(time);

            return new ConceptSemanticsResponse.StampInfo(status, author, module, path, time, formattedTime);
        } catch (Exception e) {
            log.warn("Failed to build STAMP info for stampNid {}: {}", stampNid, e.getMessage());
            return new ConceptSemanticsResponse.StampInfo(null, null, null, null, null, null);
        }
    }

    @Override
    public ConceptChangeHistoryResponse getConceptChangeHistory(String conceptId) {
        try {
            PublicId publicId = primitive.getPublicId(conceptId);
            int conceptNid = EntityService.get().nidForPublicId(publicId);

            // Get the concept description
            String conceptDescription = Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(conceptNid)
                    .orElse("Unknown concept");

            // Get change chronology for the concept itself
            ChangeChronology conceptChronology = Calculators.View.Default()
                    .stampCalculator()
                    .changeChronology(conceptNid);
            List<ConceptChangeHistoryResponse.VersionChange> conceptChanges =
                    convertToConceptVersionChanges(conceptChronology);

            // Get all semantics attached to this concept and their change histories
            int[] semanticNids = EntityService.get().semanticNidsForComponent(conceptNid);
            List<ConceptChangeHistoryResponse.SemanticChangeHistory> semanticChanges = new ArrayList<>();

            for (int semanticNid : semanticNids) {
                ConceptChangeHistoryResponse.SemanticChangeHistory semanticHistory =
                        buildSemanticChangeHistory(semanticNid);
                if (semanticHistory != null) {
                    semanticChanges.add(semanticHistory);
                }
            }

            return ConceptChangeHistoryResponse.success(conceptId, conceptDescription, conceptChanges, semanticChanges);
        } catch (Exception e) {
            log.error("Failed to get concept change history for {}: {}", conceptId, e.getMessage(), e);
            return ConceptChangeHistoryResponse.error(conceptId, e.getMessage());
        }
    }

    private List<ConceptChangeHistoryResponse.VersionChange> convertToConceptVersionChanges(ChangeChronology changeChronology) {
        List<ConceptChangeHistoryResponse.VersionChange> versionChanges = new ArrayList<>();

        for (VersionChangeRecord versionChange : changeChronology.changeRecords()) {
            ConceptChangeHistoryResponse.StampInfo stampInfo = buildConceptStampInfo(versionChange.stampNid());
            List<ConceptChangeHistoryResponse.FieldChange> fieldChanges = convertToConceptFieldChanges(versionChange.changes());
            versionChanges.add(new ConceptChangeHistoryResponse.VersionChange(stampInfo, fieldChanges));
        }

        return versionChanges;
    }

    private ConceptChangeHistoryResponse.StampInfo buildConceptStampInfo(int stampNid) {
        try {
            StampEntity<?> stampEntity = EntityService.get().getStampFast(stampNid);
            if (stampEntity == null) {
                return new ConceptChangeHistoryResponse.StampInfo(null, null, null, null, null, null);
            }

            String status = getDescriptionForNid(stampEntity.stateNid());
            String author = getDescriptionForNid(stampEntity.authorNid());
            String module = getDescriptionForNid(stampEntity.moduleNid());
            String path = getDescriptionForNid(stampEntity.pathNid());
            long time = stampEntity.time();
            String formattedTime = formatTimestamp(time);

            return new ConceptChangeHistoryResponse.StampInfo(status, author, module, path, time, formattedTime);
        } catch (Exception e) {
            log.warn("Failed to build STAMP info for stampNid {}: {}", stampNid, e.getMessage());
            return new ConceptChangeHistoryResponse.StampInfo(null, null, null, null, null, null);
        }
    }

    private List<ConceptChangeHistoryResponse.FieldChange> convertToConceptFieldChanges(
            Iterable<FieldChangeRecord> fieldChangeRecords) {
        List<ConceptChangeHistoryResponse.FieldChange> fieldChanges = new ArrayList<>();

        for (FieldChangeRecord fieldChange : fieldChangeRecords) {
            String fieldName = determineFieldName(fieldChange);
            Integer fieldIndex = fieldChange.currentValue() != null
                    ? fieldChange.currentValue().indexInPattern()
                    : (fieldChange.priorValue() != null ? fieldChange.priorValue().indexInPattern() : null);

            String priorValue = fieldChange.priorValue() != null
                    ? formatFieldValue(fieldChange.priorValue().value())
                    : null;
            String currentValue = fieldChange.currentValue() != null
                    ? formatFieldValue(fieldChange.currentValue().value())
                    : null;

            String changeType = determineChangeType(priorValue, currentValue);

            fieldChanges.add(new ConceptChangeHistoryResponse.FieldChange(
                    fieldName, fieldIndex, priorValue, currentValue, changeType));
        }

        return fieldChanges;
    }

    private ConceptChangeHistoryResponse.SemanticChangeHistory buildSemanticChangeHistory(int semanticNid) {
        try {
            Entity<?> entity = EntityService.get().getEntityFast(semanticNid);
            if (!(entity instanceof SemanticEntity<?> semanticEntity)) {
                return null;
            }

            // Get the semantic's public ID
            String semanticId = semanticEntity.publicId().asUuidList().get(0).toString();

            // Get the pattern name
            String patternName = getDescriptionForNid(semanticEntity.patternNid());

            // Get a summary of the semantic content (first field value if available)
            String summary = getSemanticSummary(semanticEntity);

            // Get change chronology for this semantic
            ChangeChronology semanticChronology = Calculators.View.Default()
                    .stampCalculator()
                    .changeChronology(semanticNid);
            List<ConceptChangeHistoryResponse.VersionChange> versionChanges =
                    convertToConceptVersionChanges(semanticChronology);

            return new ConceptChangeHistoryResponse.SemanticChangeHistory(
                    semanticId, patternName, summary, versionChanges);
        } catch (Exception e) {
            log.warn("Failed to build semantic change history for nid {}: {}", semanticNid, e.getMessage());
            return null;
        }
    }

    private String getSemanticSummary(SemanticEntity<?> semanticEntity) {
        try {
            if (semanticEntity.versions().isEmpty()) {
                return null;
            }
            SemanticEntityVersion latestVersion = semanticEntity.versions().get(
                    semanticEntity.versions().size() - 1);

            if (latestVersion.fieldValues().isEmpty()) {
                return null;
            }

            // Get the first field value as summary
            Object firstField = latestVersion.fieldValues().get(0);
            String summary = formatFieldValue(firstField);

            // Truncate if too long
            if (summary != null && summary.length() > 100) {
                summary = summary.substring(0, 97) + "...";
            }

            return summary;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String saveChanges() {
        log.info("Saving pending changes to persistent storage...");
        try {
            PrimitiveData.save();
            log.info("Changes saved successfully to persistent storage");
            return "Changes saved successfully to persistent storage. Changes will now survive server restarts.";
        } catch (Exception e) {
            log.error("Failed to save changes: {}", e.getMessage(), e);
            return "Failed to save changes: " + e.getMessage();
        }
    }

    @Override
    public String discardChanges() {
        log.info("Discarding pending changes...");
        try {
            // To discard changes, we need to reload the data from disk
            // This is a more complex operation that may require restarting the data provider
            // For now, we'll just note that changes will be lost on restart
            log.warn("Discard changes requested. Pending changes will be lost when the server restarts.");
            return "Pending changes have been marked for discard. " +
                   "Restart the server to reload data from the last saved state. " +
                   "Note: Any changes made since the last save will be lost.";
        } catch (Exception e) {
            log.error("Failed to discard changes: {}", e.getMessage(), e);
            return "Failed to discard changes: " + e.getMessage();
        }
    }

    @Override
    public DescendantOperationResponse addDescendant(String parentConceptId, String descendantConceptId) {
        try {
            PublicId parentPublicId = primitive.getPublicId(parentConceptId);
            PublicId descendantPublicId = primitive.getPublicId(descendantConceptId);

            int parentNid = EntityService.get().nidForPublicId(parentPublicId);
            int descendantNid = EntityService.get().nidForPublicId(descendantPublicId);

            // Get description for the descendant
            String descendantDescription = Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(descendantNid)
                    .orElse("Unknown concept");

            // Create a new semantic using STATED_NAVIGATION_PATTERN
            // Field 0: Component ID Set for relationship destinations (parents)
            // Field 1: Component ID Set for relationship origins (this would be for reverse lookup)
            UUID semanticUuid = UUID.randomUUID();
            long currentTime = System.currentTimeMillis();

            Transaction transaction = Transaction.make("Add descendant " + descendantDescription + " to parent");

            try {
                StampEntity<?> stamp = transaction.getStamp(
                        dev.ikm.tinkar.terms.State.ACTIVE,
                        currentTime,
                        TinkarTerm.USER.nid(),
                        TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                        TinkarTerm.DEVELOPMENT_PATH.nid()
                );

                // Build the semantic record with STATED_NAVIGATION_PATTERN
                // The descendant concept references the parent as its destination (IS-A relationship)
                IntIdSet destinationSet = IntIds.set.of(parentNid);
                IntIdSet originSet = IntIds.set.empty();

                SemanticRecord semanticRecord = SemanticRecord.build(
                        semanticUuid,
                        TinkarTerm.STATED_NAVIGATION_PATTERN.nid(),
                        descendantNid,  // The semantic is attached to the descendant concept
                        stamp.versions().get(0),
                        Lists.immutable.of(destinationSet, originSet)
                );

                EntityService.get().putEntity(semanticRecord);
                transaction.addComponent(semanticRecord);
                transaction.commit();

                log.info("Created navigation semantic {} making {} a descendant of {}",
                        semanticUuid, descendantConceptId, parentConceptId);

                return DescendantOperationResponse.success(
                        parentConceptId,
                        descendantConceptId,
                        descendantDescription,
                        "CREATED"
                );

            } catch (Exception e) {
                transaction.cancel();
                throw e;
            }

        } catch (Exception e) {
            log.error("Failed to add descendant {} to parent {}: {}",
                    descendantConceptId, parentConceptId, e.getMessage(), e);
            return DescendantOperationResponse.error(parentConceptId, descendantConceptId, e.getMessage());
        }
    }

    @Override
    public DescendantOperationResponse createAndAddDescendant(String parentConceptId, String conceptName) {
        try {
            PublicId parentPublicId = primitive.getPublicId(parentConceptId);
            int parentNid = EntityService.get().nidForPublicId(parentPublicId);

            // Create a new concept with a new UUID
            UUID newConceptUuid = UUID.randomUUID();
            PublicId newConceptPublicId = PublicIds.of(newConceptUuid);
            long currentTime = System.currentTimeMillis();

            Transaction transaction = Transaction.make("Create new concept: " + conceptName);

            try {
                // Create STAMP for the new concept
                StampEntity<?> stamp = transaction.getStamp(
                        dev.ikm.tinkar.terms.State.ACTIVE,
                        currentTime,
                        TinkarTerm.USER.nid(),
                        TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                        TinkarTerm.DEVELOPMENT_PATH.nid()
                );

                // Create the concept record
                dev.ikm.tinkar.entity.ConceptRecord conceptRecord =
                        dev.ikm.tinkar.entity.ConceptRecord.build(
                                newConceptUuid,
                                stamp.versions().get(0)
                        );

                EntityService.get().putEntity(conceptRecord);
                transaction.addComponent(conceptRecord);

                // Get the NID for the newly created concept
                int newConceptNid = EntityService.get().nidForPublicId(newConceptPublicId);

                // Create a fully qualified name semantic for the concept
                UUID fqnSemanticUuid = UUID.randomUUID();
                SemanticRecord fqnSemantic = SemanticRecord.build(
                        fqnSemanticUuid,
                        TinkarTerm.DESCRIPTION_PATTERN.nid(),
                        newConceptNid,
                        stamp.versions().get(0),
                        Lists.immutable.of(
                                TinkarTerm.ENGLISH_LANGUAGE.publicId(),
                                conceptName,
                                TinkarTerm.DESCRIPTION_CASE_SIGNIFICANCE.publicId(),
                                TinkarTerm.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE.publicId()
                        )
                );

                EntityService.get().putEntity(fqnSemantic);
                transaction.addComponent(fqnSemantic);

                // Update the parent's navigation semantic to include the new child
                // The semantic is attached to the PARENT with CHILDREN in the destination field
                // This allows NavigationCalculator.childrenOf(parent) to find all children

                // Find existing navigation semantic for the parent
                int[] parentNavSemantics = EntityService.get().semanticNidsForComponentOfPattern(
                        parentNid, TinkarTerm.INFERRED_NAVIGATION_PATTERN.nid());

                IntIdSet destinationSet;
                IntIdSet originSet;
                UUID navSemanticUuid;

                if (parentNavSemantics.length > 0) {
                    // Parent already has a navigation semantic - update it by adding the new child
                    Entity<?> existingEntity = EntityService.get().getEntityFast(parentNavSemantics[0]);
                    SemanticEntity<?> existingSemantic = (SemanticEntity<?>) existingEntity;
                    navSemanticUuid = existingSemantic.publicId().asUuidArray()[0];

                    // Get the existing field values
                    SemanticEntityVersion latestVersion = existingSemantic.versions().get(
                            existingSemantic.versions().size() - 1);
                    IntIdSet existingDestination = (IntIdSet) latestVersion.fieldValues().get(0);
                    IntIdSet existingOrigin = (IntIdSet) latestVersion.fieldValues().get(1);

                    // Add the new child to the destination set
                    MutableIntSet mutableDest = IntSets.mutable.of(existingDestination.toArray());
                    mutableDest.add(newConceptNid);
                    destinationSet = IntIds.set.of(mutableDest.toArray());
                    originSet = existingOrigin;

                    log.debug("Updating existing navigation semantic {} to include new child", navSemanticUuid);
                } else {
                    // Parent doesn't have a navigation semantic yet - create a new one
                    navSemanticUuid = dev.ikm.tinkar.common.util.uuid.UuidT5Generator.singleSemanticUuid(
                            EntityService.get().getEntityFast(TinkarTerm.INFERRED_NAVIGATION_PATTERN.nid()),
                            EntityService.get().getEntityFast(parentNid));
                    destinationSet = IntIds.set.of(newConceptNid);
                    originSet = IntIds.set.empty();

                    log.debug("Creating new navigation semantic {} for parent with first child", navSemanticUuid);
                }

                SemanticRecord navSemantic = SemanticRecord.build(
                        navSemanticUuid,
                        TinkarTerm.INFERRED_NAVIGATION_PATTERN.nid(),  // Use INFERRED pattern (default coordinate)
                        parentNid,  // Attached to PARENT, not child
                        stamp.versions().get(0),
                        Lists.immutable.of(destinationSet, originSet)
                );

                EntityService.get().putEntity(navSemantic);
                transaction.addComponent(navSemantic);

                transaction.commit();

                log.info("Created new concept {} with name '{}' as descendant of {}",
                        newConceptUuid, conceptName, parentConceptId);

                // Verify the concept and navigation semantic were created
                log.debug("Verifying concept creation - NID: {}, PublicId: {}", newConceptNid, newConceptPublicId);
                Entity<?> verifyEntity = EntityService.get().getEntityFast(newConceptNid);
                if (verifyEntity != null) {
                    log.debug("Concept verified: {}", verifyEntity.publicId());
                } else {
                    log.warn("Could not verify concept creation immediately");
                }

                // Verify navigation semantic is attached to parent
                int[] semanticNids = EntityService.get().semanticNidsForComponentOfPattern(
                        parentNid, TinkarTerm.INFERRED_NAVIGATION_PATTERN.nid());
                log.debug("Parent now has {} INFERRED navigation semantics (should have increased by 1)", semanticNids.length);

                // Clear the navigation calculator cache so it picks up the new navigation semantic
                log.debug("Clearing navigation calculator cache to refresh descendant relationships");
                dev.ikm.tinkar.common.service.CachingService.clearAll();

                // Immediately save to ensure data is persisted
                log.debug("Saving changes to persistent storage");
                try {
                    PrimitiveData.save();
                    log.debug("Changes saved successfully");
                } catch (Exception saveEx) {
                    log.error("Failed to save changes after creating concept: {}", saveEx.getMessage(), saveEx);
                }

                // Debug: Test if navigation semantic is queryable and verify structure
                log.debug("Testing if navigation semantic is queryable from parent's perspective...");
                try {
                    int[] verifyParentNavSemantics = PrimitiveData.get().semanticNidsForComponentOfPattern(
                        parentNid, TinkarTerm.INFERRED_NAVIGATION_PATTERN.nid());
                    log.debug("Parent {} has {} total INFERRED navigation semantics", parentConceptId, verifyParentNavSemantics.length);

                    if (verifyParentNavSemantics.length > 0) {
                        // Find and verify the newly created semantic
                        for (int semanticNid : verifyParentNavSemantics) {
                            Entity<?> semanticEntity = EntityService.get().getEntityFast(semanticNid);
                            if (semanticEntity instanceof SemanticEntity<?> semantic) {
                                if (!semantic.versions().isEmpty()) {
                                    SemanticEntityVersion version = semantic.versions().get(semantic.versions().size() - 1);
                                    Object destinationField = version.fieldValues().get(0);
                                    // Check if this semantic contains our new child
                                    if (destinationField instanceof IntIdSet destSet && destSet.contains(newConceptNid)) {
                                        log.debug("Found navigation semantic linking parent to new child:");
                                        log.debug("  - Semantic nid: {}", semanticNid);
                                        log.debug("  - Attached to parent nid: {}", semantic.referencedComponentNid());
                                        log.debug("  - Destination field contains child nid: {}", newConceptNid);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.error("Failed to query navigation semantics: {}", ex.getMessage(), ex);
                }

                return DescendantOperationResponse.success(
                        parentConceptId,
                        newConceptUuid.toString(),
                        conceptName,
                        "CREATED"
                );

            } catch (Exception e) {
                transaction.cancel();
                throw e;
            }

        } catch (Exception e) {
            log.error("Failed to create and add descendant to parent {}: {}",
                    parentConceptId, e.getMessage(), e);
            return DescendantOperationResponse.error(parentConceptId, null, e.getMessage());
        }
    }

    @Override
    public DescendantOperationResponse removeDescendant(String parentConceptId, String descendantConceptId) {
        try {
            PublicId parentPublicId = primitive.getPublicId(parentConceptId);
            PublicId descendantPublicId = primitive.getPublicId(descendantConceptId);

            int parentNid = EntityService.get().nidForPublicId(parentPublicId);
            int descendantNid = EntityService.get().nidForPublicId(descendantPublicId);

            // Get description for the descendant
            String descendantDescription = Calculators.View.Default()
                    .languageCalculator()
                    .getRegularDescriptionText(descendantNid)
                    .orElse("Unknown concept");

            // Find the navigation semantic that links this descendant to the parent
            int[] semanticNids = EntityService.get().semanticNidsForComponentOfPattern(
                    descendantNid, TinkarTerm.STATED_NAVIGATION_PATTERN.nid());

            UUID foundSemanticUuid = null;
            int foundSemanticNid = 0;

            for (int semanticNid : semanticNids) {
                Entity<?> entity = EntityService.get().getEntityFast(semanticNid);
                if (entity instanceof SemanticEntity<?> semanticEntity) {
                    // Check if this semantic references the parent
                    if (!semanticEntity.versions().isEmpty()) {
                        SemanticEntityVersion version = semanticEntity.versions().get(
                                semanticEntity.versions().size() - 1);
                        Object[] fields = version.fieldValues().toArray();
                        if (fields.length > 0 && fields[0] instanceof IntIdSet destinationSet) {
                            if (destinationSet.contains(parentNid)) {
                                foundSemanticUuid = semanticEntity.publicId().asUuidList().get(0);
                                foundSemanticNid = semanticNid;
                                break;
                            }
                        }
                    }
                }
            }

            if (foundSemanticUuid == null) {
                return DescendantOperationResponse.error(
                        parentConceptId,
                        descendantConceptId,
                        "No navigation relationship found between parent and descendant"
                );
            }

            // Create a new version with INACTIVE state to "delete" the relationship
            long currentTime = System.currentTimeMillis();
            Transaction transaction = Transaction.make("Remove descendant " + descendantDescription + " from parent");

            try {
                StampEntity<?> stamp = transaction.getStamp(
                        dev.ikm.tinkar.terms.State.INACTIVE,  // Mark as inactive to remove
                        currentTime,
                        TinkarTerm.USER.nid(),
                        TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                        TinkarTerm.DEVELOPMENT_PATH.nid()
                );

                // Get the existing semantic and add a new inactive version
                Entity<?> entity = EntityService.get().getEntityFast(foundSemanticNid);
                if (entity instanceof SemanticRecord semanticRecord) {
                    SemanticEntityVersion latestVersion = semanticRecord.versions().get(
                            semanticRecord.versions().size() - 1);

                    // Create new version with same fields but INACTIVE state
                    SemanticVersionRecord newVersion = new SemanticVersionRecord(
                            semanticRecord,
                            stamp.versions().get(0).stampNid(),
                            latestVersion.fieldValues()
                    );

                    // Use RecordListBuilder to properly add the new version
                    RecordListBuilder<SemanticVersionRecord> versionBuilder = RecordListBuilder.make();
                    for (var version : semanticRecord.versions()) {
                        versionBuilder.add((SemanticVersionRecord) version);
                    }
                    versionBuilder.add(newVersion);

                    SemanticRecord updatedRecord = semanticRecord.withVersions(versionBuilder.build());

                    EntityService.get().putEntity(updatedRecord);
                    transaction.addComponent(updatedRecord);
                }

                transaction.commit();

                log.info("Removed navigation semantic {} - {} is no longer a descendant of {}",
                        foundSemanticUuid, descendantConceptId, parentConceptId);

                return DescendantOperationResponse.success(
                        parentConceptId,
                        descendantConceptId,
                        descendantDescription,
                        "REMOVED"
                );

            } catch (Exception e) {
                transaction.cancel();
                throw e;
            }

        } catch (Exception e) {
            log.error("Failed to remove descendant {} from parent {}: {}",
                    descendantConceptId, parentConceptId, e.getMessage(), e);
            return DescendantOperationResponse.error(parentConceptId, descendantConceptId, e.getMessage());
        }
    }
}
