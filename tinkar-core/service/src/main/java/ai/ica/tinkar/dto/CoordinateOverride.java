package ai.ica.tinkar.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Optional coordinate overrides for Tier 2 (Knowledge Graph) queries.
 * Any null field means "use server default".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Optional coordinate overrides for controlling query behavior. Null fields use server defaults.")
public record CoordinateOverride(
        @Schema(description = "Allowed status states: ACTIVE, INACTIVE, or ACTIVE_AND_INACTIVE. Default: ACTIVE_AND_INACTIVE")
        String allowedStates,

        @Schema(description = "Position time as epoch milliseconds. Null means latest.")
        Long positionTime,

        @Schema(description = "UUID of the path concept (e.g., development path, master path). Null means development path.")
        String positionPathId,

        @Schema(description = "UUIDs of module concepts to include. Null or empty means no module filter.")
        List<String> moduleIds,

        @Schema(description = "UUIDs of module concepts to exclude. Null or empty means no exclusions.")
        List<String> excludedModuleIds,

        @Schema(description = "Navigation premise type: STATED or INFERRED. Default: INFERRED.")
        PremiseType premiseType) {
}
