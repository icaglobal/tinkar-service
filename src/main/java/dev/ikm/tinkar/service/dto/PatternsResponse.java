package dev.ikm.tinkar.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Every pattern in the knowledge base, with its definition: what its semantics mean, what they
 * are for, and the fields each one holds.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Every pattern in the knowledge base, with its definition")
public record PatternsResponse(
        @Schema(description = "The patterns, by name") List<PatternSummary> patterns,

        @Schema(description = "Whether the query was successful") Boolean success,

        @Schema(description = "Error message if query failed") String errorMessage,

        @Schema(description = "Unix epoch milliseconds when this response was generated") Long createdAt) {

    public PatternsResponse {
        patterns = patterns == null ? null : List.copyOf(patterns);
    }

    /** One pattern and its definition, as of its latest version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A pattern and its definition")
    public record PatternSummary(
            @Schema(description = "Pattern public ID (UUID)") String patternId,

            @Schema(description = "Pattern name") String name,

            @Schema(description = "What the pattern's semantics mean") String meaning,

            @Schema(description = "What the pattern's semantics are for") String purpose,

            @Schema(description = "The fields each semantic of the pattern holds, in order") List<FieldDefinition> fields,

            @Schema(description = "STAMP of the pattern's latest version") ConceptSemanticsResponse.StampInfo stamp) {

        public PatternSummary {
            fields = fields == null ? null : List.copyOf(fields);
        }
    }

    /** One field of a pattern's semantics. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A field of a pattern's semantics")
    public record FieldDefinition(
            @Schema(description = "Field index") Integer index,

            @Schema(description = "What the field means: its name") String meaning,

            @Schema(description = "What the field is for") String purpose,

            @Schema(description = "The field's data type") String dataType) {
    }

    public static PatternsResponse success(List<PatternSummary> patterns) {
        return new PatternsResponse(patterns, true, null, System.currentTimeMillis());
    }

    public static PatternsResponse error(String errorMessage) {
        return new PatternsResponse(null, false, errorMessage, System.currentTimeMillis());
    }
}
