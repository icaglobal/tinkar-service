package dev.ikm.tinkar.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response object for creating a concept.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response for concept creation")
public record ConceptCreationResponse(
        @Schema(description = "Public ID (UUID) of the created concept") String conceptId,

        @Schema(description = "Fully qualified name given to the concept") String fullyQualifiedName,

        @Schema(description = "Concepts referenced by the necessary set") List<String> parentConceptIds,

        @Schema(description = "Whether the operation was successful") Boolean success,

        @Schema(description = "Error message if the operation failed") String errorMessage,

        @Schema(description = "Unix epoch milliseconds when this response was generated") Long createdAt) {

    /** Factory method for a successful creation. */
    public static ConceptCreationResponse success(String conceptId, String fullyQualifiedName,
                                                  List<String> parentConceptIds) {
        return new ConceptCreationResponse(conceptId, fullyQualifiedName, parentConceptIds,
                true, null, System.currentTimeMillis());
    }

    /** Factory method for a failed creation. */
    public static ConceptCreationResponse error(String fullyQualifiedName, String errorMessage) {
        return new ConceptCreationResponse(null, fullyQualifiedName, null,
                false, errorMessage, System.currentTimeMillis());
    }
}
