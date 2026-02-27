package ai.ica.tinkar.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for saving a named coordinate configuration to the dataset.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request body for saving a named coordinate configuration.")
public record SavedCoordinateRequest(

        @Schema(description = "Human-readable name for this coordinate set.", example = "SNOMED-Active-Inferred")
        String name,

        @Schema(description = "The coordinate settings. Null fields use server defaults.")
        CoordinateOverride settings) {
}
