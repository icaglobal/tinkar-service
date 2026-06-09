package dev.ikm.tinkar.service.controller.knowledgegraph;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.service.dto.SavedLanguageCoordinateResponse;
import dev.ikm.tinkar.service.dto.SavedNavigationCoordinateResponse;
import dev.ikm.tinkar.service.dto.SavedStampCoordinateResponse;
import dev.ikm.tinkar.service.dto.StampCoordinateDto;
import dev.ikm.tinkar.service.proto.KnowledgeGraphConceptRequest;
import dev.ikm.tinkar.service.proto.ListLanguageCoordinatesRequest;
import dev.ikm.tinkar.service.proto.ListLanguageCoordinatesResponse;
import dev.ikm.tinkar.service.proto.ListNavigationCoordinatesRequest;
import dev.ikm.tinkar.service.proto.ListNavigationCoordinatesResponse;
import dev.ikm.tinkar.service.proto.ListStampCoordinatesRequest;
import dev.ikm.tinkar.service.proto.ListStampCoordinatesResponse;
import dev.ikm.tinkar.service.proto.SaveLanguageCoordinateRequest;
import dev.ikm.tinkar.service.proto.SaveNavigationCoordinateRequest;
import dev.ikm.tinkar.service.proto.SaveStampCoordinateRequest;
import dev.ikm.tinkar.service.proto.SemanticsWithCoordinateRequest;
import dev.ikm.tinkar.service.proto.TinkarConceptSemanticsResponse;
import dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse;
import dev.ikm.tinkar.service.service.CoordinateFactory;
import dev.ikm.tinkar.service.service.CoordinateStoreService;
import dev.ikm.tinkar.service.service.TinkarService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link KnowledgeGraphGrpcController}.
 * No Spring context — uses Mockito only.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphGrpcControllerTest {

    @Mock
    private TinkarService tinkarService;

    @Mock
    private CoordinateStoreService coordinateStoreService;

    @InjectMocks
    private KnowledgeGraphGrpcController controller;

    // ── helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> StreamObserver<T> mockObserver() {
        return Mockito.mock(StreamObserver.class);
    }

    private static KnowledgeGraphConceptRequest defaultConceptRequest(String conceptId) {
        return KnowledgeGraphConceptRequest.newBuilder()
                .setPublicId(dev.ikm.tinkar.schema.PublicId.newBuilder().addUuids(conceptId))
                .build();
    }

    private static TinkarConceptSemanticsResponse emptyConceptSemanticsResponse() {
        return TinkarConceptSemanticsResponse.newBuilder().build();
    }

    private static TinkarSearchQueryResponse emptySearchQueryResponse() {
        return TinkarSearchQueryResponse.newBuilder().setSuccess(true).build();
    }

    // ── inspectConcept ─────────────────────────────────────────────────────────

    @Test
    void inspectConcept_defaultCoords_callsDefaultCalculatorAndCallsOnNext() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            TinkarConceptSemanticsResponse mockResponse = emptyConceptSemanticsResponse();
            when(tinkarService.inspectConceptProto(eq("concept-id"), same(mockCalc)))
                    .thenReturn(mockResponse);

            StreamObserver<TinkarConceptSemanticsResponse> observer = mockObserver();
            controller.inspectConcept(defaultConceptRequest("concept-id"), observer);

            verify(observer).onNext(mockResponse);
            verify(observer).onCompleted();
        }
    }

    @Test
    void inspectConcept_extractsConceptIdFromPublicId() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            when(tinkarService.inspectConceptProto(eq("my-uuid"), same(mockCalc)))
                    .thenReturn(emptyConceptSemanticsResponse());

            StreamObserver<TinkarConceptSemanticsResponse> observer = mockObserver();
            controller.inspectConcept(defaultConceptRequest("my-uuid"), observer);

            verify(tinkarService).inspectConceptProto(eq("my-uuid"), same(mockCalc));
        }
    }

    // ── getChildConcepts ───────────────────────────────────────────────────────

    @Test
    void getChildConcepts_defaultCoords_callsDefaultCalculatorAndCallsOnNext() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            TinkarSearchQueryResponse mockResponse = emptySearchQueryResponse();
            when(tinkarService.getChildConcepts(eq("concept-id"), same(mockCalc)))
                    .thenReturn(mockResponse);

            StreamObserver<TinkarSearchQueryResponse> observer = mockObserver();
            controller.getChildConcepts(defaultConceptRequest("concept-id"), observer);

            verify(observer).onNext(mockResponse);
            verify(observer).onCompleted();
        }
    }

    @Test
    void getChildConcepts_delegatesProtoDirectlyToObserver() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            TinkarSearchQueryResponse mockResponse = emptySearchQueryResponse();
            when(tinkarService.getChildConcepts(eq("parent-id"), same(mockCalc)))
                    .thenReturn(mockResponse);

            StreamObserver<TinkarSearchQueryResponse> observer = mockObserver();
            controller.getChildConcepts(defaultConceptRequest("parent-id"), observer);

            // gRPC controller passes proto directly (no toDto conversion)
            ArgumentCaptor<TinkarSearchQueryResponse> captor =
                    ArgumentCaptor.forClass(TinkarSearchQueryResponse.class);
            verify(observer).onNext(captor.capture());
            assertThat(captor.getValue()).isSameAs(mockResponse);
        }
    }

    // ── getDescendantConcepts ──────────────────────────────────────────────────

    @Test
    void getDescendantConcepts_defaultCoords_callsDefaultCalculatorAndCallsOnNext() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            TinkarSearchQueryResponse mockResponse = emptySearchQueryResponse();
            when(tinkarService.getDescendantConcepts(eq("concept-id"), same(mockCalc)))
                    .thenReturn(mockResponse);

            StreamObserver<TinkarSearchQueryResponse> observer = mockObserver();
            controller.getDescendantConcepts(defaultConceptRequest("concept-id"), observer);

            verify(observer).onNext(mockResponse);
            verify(observer).onCompleted();
        }
    }

    @Test
    void getDescendantConcepts_delegatesProtoDirectlyToObserver() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            TinkarSearchQueryResponse mockResponse = emptySearchQueryResponse();
            when(tinkarService.getDescendantConcepts(eq("ancestor-id"), same(mockCalc)))
                    .thenReturn(mockResponse);

            StreamObserver<TinkarSearchQueryResponse> observer = mockObserver();
            controller.getDescendantConcepts(defaultConceptRequest("ancestor-id"), observer);

            ArgumentCaptor<TinkarSearchQueryResponse> captor =
                    ArgumentCaptor.forClass(TinkarSearchQueryResponse.class);
            verify(observer).onNext(captor.capture());
            assertThat(captor.getValue()).isSameAs(mockResponse);
        }
    }

    // ── saveStampCoordinate ────────────────────────────────────────────────────

    @Test
    void saveStampCoordinate_noSettings_savesNullDtoAndCallsOnNext() {
        SavedStampCoordinateResponse mockSaved = Mockito.mock(SavedStampCoordinateResponse.class);
        when(mockSaved.id()).thenReturn("stamp-id");
        when(mockSaved.settings()).thenReturn(null);
        when(mockSaved.createdAt()).thenReturn("2024-01-01");
        when(coordinateStoreService.saveStamp(null)).thenReturn(mockSaved);

        StreamObserver<dev.ikm.tinkar.service.proto.SavedStampCoordinateResponse> observer = mockObserver();
        controller.saveStampCoordinate(SaveStampCoordinateRequest.newBuilder().build(), observer);

        ArgumentCaptor<dev.ikm.tinkar.service.proto.SavedStampCoordinateResponse> captor =
                ArgumentCaptor.forClass(dev.ikm.tinkar.service.proto.SavedStampCoordinateResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getId()).isEqualTo("stamp-id");
    }

    @Test
    void saveStampCoordinate_withSettings_savesConvertedDtoAndCallsOnNext() {
        // Build a request with explicit settings
        dev.ikm.tinkar.service.proto.StampCoordinateSettings settings =
                dev.ikm.tinkar.service.proto.StampCoordinateSettings.newBuilder()
                        .setAllowedStates(dev.ikm.tinkar.service.proto.AllowedStates.ACTIVE)
                        .setPositionTime(99999L)
                        .build();
        SaveStampCoordinateRequest request = SaveStampCoordinateRequest.newBuilder()
                .setSettings(settings)
                .build();

        SavedStampCoordinateResponse mockSaved = Mockito.mock(SavedStampCoordinateResponse.class);
        when(mockSaved.id()).thenReturn("derived-stamp-id");
        when(mockSaved.settings()).thenReturn(null);
        when(mockSaved.createdAt()).thenReturn("2024-01-01");
        // The controller converts proto → StampCoordinateDto before saving; capture via any()
        when(coordinateStoreService.saveStamp(any(StampCoordinateDto.class))).thenReturn(mockSaved);

        StreamObserver<dev.ikm.tinkar.service.proto.SavedStampCoordinateResponse> observer = mockObserver();
        controller.saveStampCoordinate(request, observer);

        verify(observer).onNext(any());
        verify(observer).onCompleted();
    }

    // ── listStampCoordinates ───────────────────────────────────────────────────

    @Test
    void listStampCoordinates_emptyList_sendsEmptyResponseAndCompletes() {
        when(coordinateStoreService.findAllStamp()).thenReturn(List.of());

        StreamObserver<ListStampCoordinatesResponse> observer = mockObserver();
        controller.listStampCoordinates(ListStampCoordinatesRequest.newBuilder().build(), observer);

        ArgumentCaptor<ListStampCoordinatesResponse> captor =
                ArgumentCaptor.forClass(ListStampCoordinatesResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getCoordinatesList()).isEmpty();
    }

    @Test
    void listStampCoordinates_withEntries_sendsResponseWithCoordinates() {
        SavedStampCoordinateResponse entry = Mockito.mock(SavedStampCoordinateResponse.class);
        when(entry.id()).thenReturn("stamp-id-1");
        when(entry.settings()).thenReturn(null);
        when(entry.createdAt()).thenReturn("2024-01-01");
        when(coordinateStoreService.findAllStamp()).thenReturn(List.of(entry));

        StreamObserver<ListStampCoordinatesResponse> observer = mockObserver();
        controller.listStampCoordinates(ListStampCoordinatesRequest.newBuilder().build(), observer);

        ArgumentCaptor<ListStampCoordinatesResponse> captor =
                ArgumentCaptor.forClass(ListStampCoordinatesResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getCoordinatesList()).hasSize(1);
        assertThat(captor.getValue().getCoordinates(0).getId()).isEqualTo("stamp-id-1");
    }

    // ── saveNavigationCoordinate ───────────────────────────────────────────────

    @Test
    void saveNavigationCoordinate_noSettings_savesNullDtoAndCallsOnNext() {
        SavedNavigationCoordinateResponse mockSaved = Mockito.mock(SavedNavigationCoordinateResponse.class);
        when(mockSaved.id()).thenReturn("nav-id");
        when(mockSaved.settings()).thenReturn(null);
        when(mockSaved.createdAt()).thenReturn("2024-01-01");
        when(coordinateStoreService.saveNavigation(null)).thenReturn(mockSaved);

        StreamObserver<dev.ikm.tinkar.service.proto.SavedNavigationCoordinateResponse> observer = mockObserver();
        controller.saveNavigationCoordinate(
                SaveNavigationCoordinateRequest.newBuilder().build(), observer);

        ArgumentCaptor<dev.ikm.tinkar.service.proto.SavedNavigationCoordinateResponse> captor =
                ArgumentCaptor.forClass(dev.ikm.tinkar.service.proto.SavedNavigationCoordinateResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getId()).isEqualTo("nav-id");
    }

    // ── listNavigationCoordinates ──────────────────────────────────────────────

    @Test
    void listNavigationCoordinates_emptyList_sendsEmptyResponseAndCompletes() {
        when(coordinateStoreService.findAllNavigation()).thenReturn(List.of());

        StreamObserver<ListNavigationCoordinatesResponse> observer = mockObserver();
        controller.listNavigationCoordinates(
                ListNavigationCoordinatesRequest.newBuilder().build(), observer);

        ArgumentCaptor<ListNavigationCoordinatesResponse> captor =
                ArgumentCaptor.forClass(ListNavigationCoordinatesResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getCoordinatesList()).isEmpty();
    }

    @Test
    void listNavigationCoordinates_withEntries_sendsResponseWithCoordinates() {
        SavedNavigationCoordinateResponse entry = Mockito.mock(SavedNavigationCoordinateResponse.class);
        when(entry.id()).thenReturn("nav-id-1");
        when(entry.settings()).thenReturn(null);
        when(entry.createdAt()).thenReturn("2024-01-01");
        when(coordinateStoreService.findAllNavigation()).thenReturn(List.of(entry));

        StreamObserver<ListNavigationCoordinatesResponse> observer = mockObserver();
        controller.listNavigationCoordinates(
                ListNavigationCoordinatesRequest.newBuilder().build(), observer);

        ArgumentCaptor<ListNavigationCoordinatesResponse> captor =
                ArgumentCaptor.forClass(ListNavigationCoordinatesResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getCoordinatesList()).hasSize(1);
        assertThat(captor.getValue().getCoordinates(0).getId()).isEqualTo("nav-id-1");
    }

    // ── saveLanguageCoordinate ─────────────────────────────────────────────────

    @Test
    void saveLanguageCoordinate_noSettings_savesNullDtoAndCallsOnNext() {
        SavedLanguageCoordinateResponse mockSaved = Mockito.mock(SavedLanguageCoordinateResponse.class);
        when(mockSaved.id()).thenReturn("lang-id");
        when(mockSaved.settings()).thenReturn(null);
        when(mockSaved.createdAt()).thenReturn("2024-01-01");
        when(coordinateStoreService.saveLanguage(null)).thenReturn(mockSaved);

        StreamObserver<dev.ikm.tinkar.service.proto.SavedLanguageCoordinateResponse> observer = mockObserver();
        controller.saveLanguageCoordinate(
                SaveLanguageCoordinateRequest.newBuilder().build(), observer);

        ArgumentCaptor<dev.ikm.tinkar.service.proto.SavedLanguageCoordinateResponse> captor =
                ArgumentCaptor.forClass(dev.ikm.tinkar.service.proto.SavedLanguageCoordinateResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getId()).isEqualTo("lang-id");
    }

    // ── listLanguageCoordinates ────────────────────────────────────────────────

    @Test
    void listLanguageCoordinates_emptyList_sendsEmptyResponseAndCompletes() {
        when(coordinateStoreService.findAllLanguage()).thenReturn(List.of());

        StreamObserver<ListLanguageCoordinatesResponse> observer = mockObserver();
        controller.listLanguageCoordinates(
                ListLanguageCoordinatesRequest.newBuilder().build(), observer);

        ArgumentCaptor<ListLanguageCoordinatesResponse> captor =
                ArgumentCaptor.forClass(ListLanguageCoordinatesResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getCoordinatesList()).isEmpty();
    }

    @Test
    void listLanguageCoordinates_withEntries_sendsResponseWithCoordinates() {
        SavedLanguageCoordinateResponse entry = Mockito.mock(SavedLanguageCoordinateResponse.class);
        when(entry.id()).thenReturn("lang-id-1");
        when(entry.settings()).thenReturn(null);
        when(entry.createdAt()).thenReturn("2024-01-01");
        when(coordinateStoreService.findAllLanguage()).thenReturn(List.of(entry));

        StreamObserver<ListLanguageCoordinatesResponse> observer = mockObserver();
        controller.listLanguageCoordinates(
                ListLanguageCoordinatesRequest.newBuilder().build(), observer);

        ArgumentCaptor<ListLanguageCoordinatesResponse> captor =
                ArgumentCaptor.forClass(ListLanguageCoordinatesResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getCoordinatesList()).hasSize(1);
        assertThat(captor.getValue().getCoordinates(0).getId()).isEqualTo("lang-id-1");
    }

    // ── getSemanticsWithCoordinate ─────────────────────────────────────────────

    @Test
    void getSemanticsWithCoordinate_emptyIds_buildsCalculatorFromNullCoordsAndCallsOnNext() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord mockStamp =
                    Mockito.mock(dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord.class);
            dev.ikm.tinkar.coordinate.language.LanguageCoordinateRecord mockLang =
                    Mockito.mock(dev.ikm.tinkar.coordinate.language.LanguageCoordinateRecord.class);
            dev.ikm.tinkar.coordinate.navigation.NavigationCoordinateRecord mockNav =
                    Mockito.mock(dev.ikm.tinkar.coordinate.navigation.NavigationCoordinateRecord.class);
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);

            cfMock.when(() -> CoordinateFactory.buildStampCoordinate(null)).thenReturn(mockStamp);
            cfMock.when(() -> CoordinateFactory.buildLanguageCoordinate(null)).thenReturn(mockLang);
            cfMock.when(() -> CoordinateFactory.buildNavigationCoordinate(null)).thenReturn(mockNav);
            cfMock.when(() -> CoordinateFactory.buildCalculator(mockStamp, mockLang, mockNav))
                    .thenReturn(mockCalc);

            TinkarConceptSemanticsResponse mockResponse = emptyConceptSemanticsResponse();
            when(tinkarService.inspectConceptProto(eq(""), same(mockCalc))).thenReturn(mockResponse);

            StreamObserver<TinkarConceptSemanticsResponse> observer = mockObserver();
            // Empty request: all IDs are empty strings → treated as null
            controller.getSemanticsWithCoordinate(
                    SemanticsWithCoordinateRequest.newBuilder().build(), observer);

            verify(observer).onNext(mockResponse);
            verify(observer).onCompleted();
        }
    }

    @Test
    void getSemanticsWithCoordinate_callsInspectConceptProtoWithBuiltCalculator() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord mockStamp =
                    Mockito.mock(dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord.class);
            dev.ikm.tinkar.coordinate.language.LanguageCoordinateRecord mockLang =
                    Mockito.mock(dev.ikm.tinkar.coordinate.language.LanguageCoordinateRecord.class);
            dev.ikm.tinkar.coordinate.navigation.NavigationCoordinateRecord mockNav =
                    Mockito.mock(dev.ikm.tinkar.coordinate.navigation.NavigationCoordinateRecord.class);
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);

            cfMock.when(() -> CoordinateFactory.buildStampCoordinate(null)).thenReturn(mockStamp);
            cfMock.when(() -> CoordinateFactory.buildLanguageCoordinate(null)).thenReturn(mockLang);
            cfMock.when(() -> CoordinateFactory.buildNavigationCoordinate(null)).thenReturn(mockNav);
            cfMock.when(() -> CoordinateFactory.buildCalculator(mockStamp, mockLang, mockNav))
                    .thenReturn(mockCalc);

            TinkarConceptSemanticsResponse mockResponse = emptyConceptSemanticsResponse();
            when(tinkarService.inspectConceptProto(any(), same(mockCalc))).thenReturn(mockResponse);

            StreamObserver<TinkarConceptSemanticsResponse> observer = mockObserver();
            controller.getSemanticsWithCoordinate(
                    SemanticsWithCoordinateRequest.newBuilder().build(), observer);

            verify(tinkarService).inspectConceptProto(any(), same(mockCalc));
        }
    }
}
