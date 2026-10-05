package dev.ikm.tinkar.service.util;

import dev.ikm.tinkar.schema.StampVersion;
import dev.ikm.tinkar.service.dto.ConceptSearchResponse;
import dev.ikm.tinkar.service.dto.SearchSortOption;
import dev.ikm.tinkar.service.dto.TinkarSearchQueryResponse;
import dev.ikm.tinkar.service.dto.TinkarSearchQueryResponse.Descriptions;
import dev.ikm.tinkar.service.dto.TinkarSearchQueryResponse.SearchResult;
import dev.ikm.tinkar.service.dto.TinkarSearchQueryResponse.Stamp;
import dev.ikm.tinkar.service.proto.TinkarConceptSearchWithSortResponse;
import dev.ikm.tinkar.service.proto.TinkarGroupedSearchResult;
import dev.ikm.tinkar.service.proto.TinkarMatchingSemantic;
import dev.ikm.tinkar.service.proto.TinkarSearchResult;
import dev.ikm.tinkar.service.proto.TinkarSemanticSearchResult;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ProtoConversionUtils {

    private ProtoConversionUtils() {}

    // ── Proto → DTO (used by REST controllers) ──────────────────────────────

    public static TinkarSearchQueryResponse toDto(dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse proto) {
        List<SearchResult> results = proto.getResultsList().stream()
                .map(ProtoConversionUtils::toSearchResultDto)
                .toList();
        return new TinkarSearchQueryResponse(
                proto.getQuery(),
                proto.getTotalCount(),
                results,
                proto.getSuccess(),
                proto.getErrorMessage().isEmpty() ? null : proto.getErrorMessage(),
                proto.getCreatedAt() != 0 ? proto.getCreatedAt() : System.currentTimeMillis());
    }

    private static SearchResult toSearchResultDto(TinkarSearchResult proto) {
        List<String> publicIds = proto.getPublicId().getUuidsList();
        Descriptions descriptions = new Descriptions(
                proto.getDescriptions().getFullyQualifiedName(),
                proto.getDescriptions().getRegularName(),
                proto.getDescriptions().getDefinition());
        Stamp stamp = toStampDto(proto.getStamp());
        return new SearchResult(publicIds, descriptions, stamp);
    }

    private static Stamp toStampDto(StampVersion proto) {
        String statusPublicId = proto.hasStatusPublicId() ? leastUuid(proto.getStatusPublicId()) : null;
        String authorPublicId = proto.hasAuthorPublicId() ? leastUuid(proto.getAuthorPublicId()) : null;
        String modulePublicId = proto.hasModulePublicId() ? leastUuid(proto.getModulePublicId()) : null;
        String pathPublicId = proto.hasPathPublicId() ? leastUuid(proto.getPathPublicId()) : null;
        return new Stamp(statusPublicId, authorPublicId, modulePublicId, pathPublicId, proto.getTime());
    }

    /**
     * The one UUID of a wire public id that stands for its component where a single UUID must
     * (a DTO field, a lookup handle). Any of a public id's UUIDs identifies the component; the
     * least, by {@link UUID#compareTo}, is chosen so the result does not depend on the order
     * the UUIDs are listed in — as {@code PublicId.leastUuid()} chooses.
     *
     * <p>The text is passed through as the wire carries it: an element that is not a UUID is
     * not refused here, but left for whatever resolves it to refuse, and is chosen only when
     * no element is a UUID.
     *
     * @param publicId a wire public id, or null
     * @return the least UUID as a string, or null when the public id is null or lists none
     */
    public static String leastUuid(dev.ikm.tinkar.schema.PublicId publicId) {
        if (publicId == null) {
            return null;
        }
        return publicId.getUuidsList().stream()
                .min(BY_UUID)
                .orElse(null);
    }

    /** UUID text by {@link UUID#compareTo}; text that is not a UUID after every UUID, by text. */
    private static final Comparator<String> BY_UUID = Comparator
            .comparing(ProtoConversionUtils::parse, Comparator.nullsLast(Comparator.<UUID>naturalOrder()))
            .thenComparing(Comparator.naturalOrder());

    private static UUID parse(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    // ── Sort option conversion ────────────────────────────────────────────────

    public static SearchSortOption toSortOptionDto(dev.ikm.tinkar.service.proto.SearchSortOption grpcSortOption) {
        if (grpcSortOption == null) {
            return SearchSortOption.TOP_COMPONENT;
        }
        return switch (grpcSortOption) {
            case TOP_COMPONENT -> SearchSortOption.TOP_COMPONENT;
            case TOP_COMPONENT_ALPHA -> SearchSortOption.TOP_COMPONENT_ALPHA;
            case SEMANTIC -> SearchSortOption.SEMANTIC;
            case SEMANTIC_ALPHA -> SearchSortOption.SEMANTIC_ALPHA;
            default -> SearchSortOption.TOP_COMPONENT;
        };
    }

    public static dev.ikm.tinkar.service.proto.SearchSortOption toSortOptionProto(SearchSortOption dtoSortOption) {
        if (dtoSortOption == null) {
            return dev.ikm.tinkar.service.proto.SearchSortOption.TOP_COMPONENT;
        }
        return switch (dtoSortOption) {
            case TOP_COMPONENT -> dev.ikm.tinkar.service.proto.SearchSortOption.TOP_COMPONENT;
            case TOP_COMPONENT_ALPHA -> dev.ikm.tinkar.service.proto.SearchSortOption.TOP_COMPONENT_ALPHA;
            case SEMANTIC -> dev.ikm.tinkar.service.proto.SearchSortOption.SEMANTIC;
            case SEMANTIC_ALPHA -> dev.ikm.tinkar.service.proto.SearchSortOption.SEMANTIC_ALPHA;
        };
    }

    // ── DTO → Proto (used by gRPC controllers for conceptSearchWithSort) ────

    public static TinkarConceptSearchWithSortResponse toConceptSearchWithSortProto(ConceptSearchResponse dtoResponse) {
        TinkarConceptSearchWithSortResponse.Builder builder = TinkarConceptSearchWithSortResponse.newBuilder()
                .setQuery(dtoResponse.query() != null ? dtoResponse.query() : "")
                .setTotalCount(dtoResponse.totalCount() != null ? dtoResponse.totalCount() : 0L)
                .setSortBy(toSortOptionProto(dtoResponse.sortBy()))
                .setSuccess(dtoResponse.success() != null && dtoResponse.success())
                .setCreatedAt(dtoResponse.createdAt() != null ? dtoResponse.createdAt() : System.currentTimeMillis());

        if (dtoResponse.errorMessage() != null) {
            builder.setErrorMessage(dtoResponse.errorMessage());
        }

        if (dtoResponse.results() != null) {
            for (ConceptSearchResponse.SemanticSearchResult result : dtoResponse.results()) {
                TinkarSemanticSearchResult.Builder resultBuilder = TinkarSemanticSearchResult.newBuilder()
                        .setFullyQualifiedName(result.fullyQualifiedName() != null ? result.fullyQualifiedName() : "")
                        .setScore(result.score() != null ? result.score() : 0f)
                        .setActive(result.active() != null && result.active());
                if (result.publicId() != null) resultBuilder.addAllPublicId(result.publicId());
                if (result.regularName() != null) resultBuilder.setRegularName(result.regularName());
                if (result.highlightedText() != null) resultBuilder.setHighlightedText(result.highlightedText());
                builder.addResults(resultBuilder.build());
            }
        }

        if (dtoResponse.groupedResults() != null) {
            for (ConceptSearchResponse.GroupedSearchResult group : dtoResponse.groupedResults()) {
                TinkarGroupedSearchResult.Builder groupBuilder = TinkarGroupedSearchResult.newBuilder()
                        .setFullyQualifiedName(group.fullyQualifiedName() != null ? group.fullyQualifiedName() : "")
                        .setTopScore(group.topScore() != null ? group.topScore() : 0f)
                        .setActive(group.active() != null && group.active());
                if (group.publicId() != null) groupBuilder.addAllPublicId(group.publicId());
                if (group.preferredName() != null) groupBuilder.setPreferredName(group.preferredName());
                if (group.highlightedName() != null) groupBuilder.setHighlightedName(group.highlightedName());

                if (group.matchingSemantics() != null) {
                    for (ConceptSearchResponse.MatchingSemantic semantic : group.matchingSemantics()) {
                        TinkarMatchingSemantic.Builder semanticBuilder = TinkarMatchingSemantic.newBuilder()
                                .setScore(semantic.score() != null ? semantic.score() : 0f);
                        if (semantic.highlightedText() != null) semanticBuilder.setHighlightedText(semantic.highlightedText());
                        if (semantic.plainText() != null) semanticBuilder.setPlainText(semantic.plainText());
                        if (semantic.fieldIndex() != null) semanticBuilder.setFieldIndex(semantic.fieldIndex());
                        if (semantic.publicId() != null) semanticBuilder.addAllPublicId(semantic.publicId());
                        groupBuilder.addMatchingSemantics(semanticBuilder.build());
                    }
                }

                builder.addGroupedResults(groupBuilder.build());
            }
        }

        return builder.build();
    }
}
