package dev.ikm.tinkar.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of a pattern's members, the semantics made by it, with the pattern's total member
 * count. Each member names the component it refers to, so a page displays without a call per
 * row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One page of a pattern's members")
public record PatternMembersResponse(
        @Schema(description = "Pattern public ID (UUID)") String patternId,

        @Schema(description = "Pattern name") String patternName,

        @Schema(description = "How many members the pattern has, unfiltered") Long totalCount,

        @Schema(description = "How many matching members were skipped before this page") Integer offset,

        @Schema(description = "Whether more matching members follow this page") Boolean hasMore,

        @Schema(description = "The members on this page") List<Member> members,

        @Schema(description = "Whether the query was successful") Boolean success,

        @Schema(description = "Error message if query failed") String errorMessage,

        @Schema(description = "Unix epoch milliseconds when this response was generated") Long createdAt) {

    public PatternMembersResponse {
        members = members == null ? null : List.copyOf(members);
    }

    /** A member of the pattern, as of its latest version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A member of a pattern")
    public record Member(
            @Schema(description = "Semantic public ID (UUID)") String semanticId,

            @Schema(description = "Public ID (UUID) of the component the semantic refers to") String referencedComponentId,

            @Schema(description = "Name of the component the semantic refers to") String referencedComponentName,

            @Schema(description = "Field values, named by the pattern's field definitions") List<NamedField> fields,

            @Schema(description = "STAMP of the semantic's latest version") ConceptSemanticsResponse.StampInfo stamp) {

        public Member {
            fields = fields == null ? null : List.copyOf(fields);
        }
    }

    /** A field value of a member, with the name its pattern gives the field. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A named field value")
    public record NamedField(
            @Schema(description = "Field name") String name,

            @Schema(description = "Field value as text") String value) {
    }

    public static PatternMembersResponse success(String patternId, String patternName, long totalCount,
                                                 int offset, boolean hasMore, List<Member> members) {
        return new PatternMembersResponse(patternId, patternName, totalCount, offset, hasMore, members,
                true, null, System.currentTimeMillis());
    }

    public static PatternMembersResponse error(String patternId, String errorMessage) {
        return new PatternMembersResponse(patternId, null, null, null, null, null,
                false, errorMessage, System.currentTimeMillis());
    }
}
