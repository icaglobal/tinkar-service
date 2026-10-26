package dev.ikm.tinkar.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What creating a pattern takes and answers: the pattern's names, what its semantics mean and are
 * for, and the fields each one holds. The same parts Komet's pattern window asks for.
 */
public final class PatternCreation {

    private PatternCreation() {
    }

    /** A new pattern. */
    @Schema(description = "A new pattern: its names, meaning, purpose and fields")
    public record CreatePatternRequest(
            @Schema(description = "The pattern's names: exactly one FULLY_QUALIFIED_NAME, and any REGULAR_NAMEs")
            List<CreateConceptRequest.Description> descriptions,

            @Schema(description = "Concept ID (UUID) of what the pattern's semantics mean") String meaningId,

            @Schema(description = "Concept ID (UUID) of what the pattern's semantics are for")
            String purposeId,

            @Schema(description = "The fields each semantic of the pattern holds, in order; may be empty")
            List<Field> fields) {

        public CreatePatternRequest {
            descriptions = descriptions == null ? List.of() : List.copyOf(descriptions);
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** A field of the new pattern. */
    @Schema(description = "A field of a new pattern")
    public record Field(
            @Schema(description = "Concept ID (UUID) of what the field means: its name") String meaningId,

            @Schema(description = "Concept ID (UUID) of what the field is for") String purposeId,

            @Schema(description = "Concept ID (UUID) of the field's data type: one of GET /patterns/field-data-types")
            String dataTypeId) {
    }

    /** What creating a pattern answers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "What creating a pattern answers")
    public record PatternCreationResponse(
            @Schema(description = "Whether the pattern was created") boolean success,

            @Schema(description = "The new pattern, as GET /patterns lists it") PatternsResponse.PatternSummary pattern,

            @Schema(description = "Why the pattern was not created") String errorMessage,

            @Schema(description = "Unix epoch milliseconds when this response was generated") Long createdAt) {

        public static PatternCreationResponse success(PatternsResponse.PatternSummary pattern) {
            return new PatternCreationResponse(true, pattern, null, System.currentTimeMillis());
        }

        public static PatternCreationResponse error(String errorMessage) {
            return new PatternCreationResponse(false, null, errorMessage, System.currentTimeMillis());
        }
    }

    /** A concept to choose from, such as a field data type. */
    @Schema(description = "A concept to choose from")
    public record Choice(
            @Schema(description = "Concept ID (UUID)") String id,

            @Schema(description = "Concept name") String name) {
    }

    /** The data types a pattern's field can have. */
    @Schema(description = "The data types a pattern's field can have")
    public record FieldDataTypesResponse(
            @Schema(description = "The data types, by name") List<Choice> dataTypes) {

        public FieldDataTypesResponse {
            dataTypes = dataTypes == null ? List.of() : List.copyOf(dataTypes);
        }
    }
}
