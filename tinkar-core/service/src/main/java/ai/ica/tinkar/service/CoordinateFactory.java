package ai.ica.tinkar.service;

import ai.ica.tinkar.dto.CoordinateOverride;
import ai.ica.tinkar.dto.PremiseType;
import dev.ikm.tinkar.common.id.IntIdList;
import dev.ikm.tinkar.common.id.IntIdSet;
import dev.ikm.tinkar.common.id.IntIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.navigation.NavigationCoordinateRecord;
import dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord;
import dev.ikm.tinkar.coordinate.stamp.StampPositionRecord;
import dev.ikm.tinkar.coordinate.stamp.StateSet;
import dev.ikm.tinkar.coordinate.view.ViewCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.entity.EntityService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * Builds a {@link ViewCalculatorWithCache} from optional coordinate overrides.
 * Unspecified fields fall back to server defaults (same as Tier 1).
 */
@Slf4j
public class CoordinateFactory {

    /**
     * Returns the server-default calculator (same as Tier 1).
     */
    public static ViewCalculatorWithCache defaultCalculator() {
        return Calculators.View.Default();
    }

    /**
     * Builds a calculator from optional coordinate overrides.
     * Any null field in the override uses the server default value.
     *
     * @param override the coordinate overrides, or null for all defaults
     * @return a ViewCalculatorWithCache configured with the merged coordinates
     */
    public static ViewCalculatorWithCache buildCalculator(CoordinateOverride override) {
        if (override == null) {
            return defaultCalculator();
        }

        // STAMP coordinate
        StateSet allowedStates = resolveAllowedStates(override.allowedStates());
        long positionTime = override.positionTime() != null ? override.positionTime() : Long.MAX_VALUE;
        int pathNid = resolvePathNid(override.positionPathId());
        IntIdSet moduleNids = resolveModuleNids(override.moduleIds());
        IntIdSet excludedModuleNids = resolveModuleNids(override.excludedModuleIds());
        IntIdList modulePriorityNids = resolveModulePriorityNids(override.modulePriorityIds());

        StampPositionRecord stampPosition = StampPositionRecord.make(positionTime, pathNid);
        StampCoordinateRecord stampCoordinate = new StampCoordinateRecord(
                allowedStates, stampPosition, moduleNids, excludedModuleNids, modulePriorityNids);

        // Navigation coordinate
        NavigationCoordinateRecord navigationCoordinate = resolveNavigation(override.premiseType());

        // Build view coordinate (language, logic, edit use defaults)
        ViewCoordinateRecord viewCoordinate = ViewCoordinateRecord.make(
                stampCoordinate,
                Coordinates.Language.UsEnglishRegularName(),
                Coordinates.Logic.ElPlusPlus(),
                navigationCoordinate,
                Coordinates.Edit.Default());

        return ViewCalculatorWithCache.getCalculator(viewCoordinate);
    }

    private static StateSet resolveAllowedStates(String allowedStates) {
        if (allowedStates == null || allowedStates.isBlank()) {
            return StateSet.ACTIVE_AND_INACTIVE;
        }
        return switch (allowedStates.toUpperCase()) {
            case "ACTIVE" -> StateSet.ACTIVE;
            case "INACTIVE" -> StateSet.INACTIVE;
            case "ACTIVE_AND_INACTIVE" -> StateSet.ACTIVE_AND_INACTIVE;
            default -> {
                log.warn("Unknown allowedStates value '{}', using default ACTIVE_AND_INACTIVE", allowedStates);
                yield StateSet.ACTIVE_AND_INACTIVE;
            }
        };
    }

    private static int resolvePathNid(String pathId) {
        if (pathId == null || pathId.isBlank()) {
            return Coordinates.Stamp.DevelopmentLatest().stampPosition().getPathForPositionNid();
        }
        try {
            PublicId publicId = PublicIds.of(UUID.fromString(pathId));
            return EntityService.get().nidForPublicId(publicId);
        } catch (Exception e) {
            log.warn("Failed to resolve path UUID '{}', using default development path: {}", pathId, e.getMessage());
            return Coordinates.Stamp.DevelopmentLatest().stampPosition().getPathForPositionNid();
        }
    }

    private static IntIdSet resolveModuleNids(List<String> moduleIds) {
        if (moduleIds == null || moduleIds.isEmpty()) {
            return IntIds.set.empty();
        }
        int[] nids = moduleIds.stream()
                .mapToInt(id -> {
                    try {
                        PublicId publicId = PublicIds.of(UUID.fromString(id));
                        return EntityService.get().nidForPublicId(publicId);
                    } catch (Exception e) {
                        log.warn("Failed to resolve module UUID '{}': {}", id, e.getMessage());
                        return Integer.MIN_VALUE;
                    }
                })
                .filter(nid -> nid != Integer.MIN_VALUE)
                .toArray();
        return IntIds.set.of(nids);
    }

    private static IntIdList resolveModulePriorityNids(List<String> modulePriorityIds) {
        if (modulePriorityIds == null || modulePriorityIds.isEmpty()) {
            return IntIds.list.empty();
        }
        int[] nids = modulePriorityIds.stream()
                .mapToInt(id -> {
                    try {
                        PublicId publicId = PublicIds.of(UUID.fromString(id));
                        return EntityService.get().nidForPublicId(publicId);
                    } catch (Exception e) {
                        log.warn("Failed to resolve module priority UUID '{}': {}", id, e.getMessage());
                        return Integer.MIN_VALUE;
                    }
                })
                .filter(nid -> nid != Integer.MIN_VALUE)
                .toArray();
        return IntIds.list.of(nids);
    }

    private static NavigationCoordinateRecord resolveNavigation(PremiseType premiseType) {
        if (premiseType == null) {
            return NavigationCoordinateRecord.makeInferred();
        }
        return switch (premiseType) {
            case STATED -> NavigationCoordinateRecord.makeStated();
            case INFERRED -> NavigationCoordinateRecord.makeInferred();
        };
    }
}
