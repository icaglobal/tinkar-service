package dev.ikm.tinkar.service.service;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.stamp.StateSet;
import dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.service.dto.CoordinateOverride;
import dev.ikm.tinkar.service.dto.StampCoordinateDto;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Unit tests for {@link CoordinateFactory}.
 *
 * <p>Navigation and language coordinate tests are omitted here because
 * {@code NavigationCoordinateRecord.makeInferred/Stated()} and all
 * {@code Coordinates.Language.*} methods call {@code KernelTerm.*.nid()},
 * which requires PrimitiveData to be running.  Those paths are exercised
 * by integration tests.
 *
 * <p>Stamp coordinate tests supply a non-null {@code positionPathId} so
 * {@code resolvePathNid} uses {@link KnownComponents} (mocked) instead
 * of {@code Coordinates.Stamp.DevelopmentLatest()} (infrastructure-bound).
 * The lookup itself is tested against a real store in
 * {@code UnknownComponentRequestTest}.
 */
class CoordinateFactoryTest {

    /** A valid UUID string used as a dummy path concept ID in stamp tests. */
    private static final String DUMMY_PATH_UUID = "550e8400-e29b-41d4-a716-446655440000";

    // ── buildCalculator(CoordinateOverride) ──────────────────────────────────

    @Test
    void buildCalculator_nullOverride_returnsDefaultCalculator() {
        try (MockedStatic<Calculators.View> calcMock = Mockito.mockStatic(Calculators.View.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            calcMock.when(() -> Calculators.View.Default()).thenReturn(mockCalc);

            ViewCalculatorWithCache result = CoordinateFactory.buildCalculator((CoordinateOverride) null);

            assertThat(result).isSameAs(mockCalc);
        }
    }

    // ── buildStampCoordinate – resolveAllowedStates branches ─────────────────
    // Providing a non-null positionPathId so resolvePathNid uses KnownComponents
    // rather than Coordinates.Stamp.DevelopmentLatest(), which needs PrimitiveData.

    @Test
    void buildStampCoordinate_activeAllowedStates_returnsActiveStateSet() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("ACTIVE", 1000L, DUMMY_PATH_UUID, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.allowedStates()).isEqualTo(StateSet.ACTIVE);
        }
    }

    @Test
    void buildStampCoordinate_inactiveAllowedStates_returnsInactiveStateSet() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("INACTIVE", 1000L, DUMMY_PATH_UUID, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.allowedStates()).isEqualTo(StateSet.INACTIVE);
        }
    }

    @Test
    void buildStampCoordinate_activeAndInactiveAllowedStates_returnsFullStateSet() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("ACTIVE_AND_INACTIVE", 1000L, DUMMY_PATH_UUID, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.allowedStates()).isEqualTo(StateSet.ACTIVE_AND_INACTIVE);
        }
    }

    @Test
    void buildStampCoordinate_unknownAllowedStates_defaultsToActiveAndInactive() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("BOGUS_STATE", 1000L, DUMMY_PATH_UUID, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.allowedStates()).isEqualTo(StateSet.ACTIVE_AND_INACTIVE);
        }
    }

    @Test
    void buildStampCoordinate_blankAllowedStates_defaultsToActiveAndInactive() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("", 1000L, DUMMY_PATH_UUID, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.allowedStates()).isEqualTo(StateSet.ACTIVE_AND_INACTIVE);
        }
    }

    @Test
    void buildStampCoordinate_nullAllowedStates_defaultsToActiveAndInactive() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto(null, 1000L, DUMMY_PATH_UUID, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.allowedStates()).isEqualTo(StateSet.ACTIVE_AND_INACTIVE);
        }
    }

    @Test
    void buildStampCoordinate_activeVsInactive_produceDifferentStateSets() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord active = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("ACTIVE", 1000L, DUMMY_PATH_UUID, null, null, null));
            StampCoordinateRecord inactive = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("INACTIVE", 1000L, DUMMY_PATH_UUID, null, null, null));

            assertThat(active.allowedStates()).isNotEqualTo(inactive.allowedStates());
        }
    }

    @Test
    void buildStampCoordinate_nullPositionTime_usesMaxValue() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("ACTIVE", null, DUMMY_PATH_UUID, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.stampPosition().time()).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Test
    void buildStampCoordinate_providedPositionTime_usesGivenTime() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("ACTIVE", 99999L, DUMMY_PATH_UUID, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.stampPosition().time()).isEqualTo(99999L);
        }
    }

    @Test
    void buildStampCoordinate_emptyModuleIds_producesEmptyModuleNids() {
        try (MockedStatic<KnownComponents> knownMock = Mockito.mockStatic(KnownComponents.class)) {
            mockKnownComponentNid(knownMock, 42);

            StampCoordinateRecord result = CoordinateFactory.buildStampCoordinate(
                    new StampCoordinateDto("ACTIVE", 1000L, DUMMY_PATH_UUID,
                            java.util.List.of(), java.util.List.of(), java.util.List.of()));

            assertThat(result).isNotNull();
            assertThat(result.moduleNids().size()).isZero();
            assertThat(result.excludedModuleNids().size()).isZero();
            assertThat(result.modulePriorityNidList().size()).isZero();
        }
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static void mockKnownComponentNid(MockedStatic<KnownComponents> knownMock, int nid) {
        knownMock.when(() -> KnownComponents.nid(any(PublicId.class))).thenReturn(OptionalInt.of(nid));
    }
}
