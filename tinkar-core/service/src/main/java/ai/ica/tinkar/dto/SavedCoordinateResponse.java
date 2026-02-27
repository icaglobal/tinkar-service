package ai.ica.tinkar.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for a saved coordinate configuration, including its assigned dataset UUID.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A saved coordinate configuration with its assigned ID.")
public record SavedCoordinateResponse(

        @Schema(description = "UUID of the coordinate concept in the dataset. Pass this value as coordinateId " +
                "to /semantics-by-coordinate.", example = "3f47a12e-bc94-4b8a-a8f2-1234567890ab")
        String id,

        @Schema(description = "Human-readable name.", example = "SNOMED-Active-Inferred")
        String name,

        @Schema(description = "The stored coordinate settings.")
        CoordinateOverride settings,

        @Schema(description = "ISO-8601 timestamp when this coordinate was saved.", example = "2026-02-27T12:00:00Z")
        String createdAt) {
}
