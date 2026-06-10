package dev.ikm.tinkar.service.controller.knowledgegraph;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.service.dto.ChangeHistoryResponse;
import dev.ikm.tinkar.service.dto.ConceptChangeHistoryResponse;
import dev.ikm.tinkar.service.dto.ConceptSemanticsResponse;
import dev.ikm.tinkar.service.dto.DescendantOperationResponse;
import dev.ikm.tinkar.service.dto.LanguageCoordinateDto;
import dev.ikm.tinkar.service.dto.NavigationCoordinateDto;
import dev.ikm.tinkar.service.dto.SavedLanguageCoordinateResponse;
import dev.ikm.tinkar.service.dto.SavedNavigationCoordinateResponse;
import dev.ikm.tinkar.service.dto.SavedStampCoordinateResponse;
import dev.ikm.tinkar.service.dto.StampCoordinateDto;
import dev.ikm.tinkar.service.dto.TinkarSearchQueryResponse;
import dev.ikm.tinkar.service.service.CoordinateFactory;
import dev.ikm.tinkar.service.service.CoordinateStoreService;
import dev.ikm.tinkar.service.service.TinkarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link KnowledgeGraphRestController}.
 * No Spring context — uses Mockito only.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphRestControllerTest {

    @Mock
    private TinkarService tinkarService;

    @Mock
    private CoordinateStoreService coordinateStoreService;

    @InjectMocks
    private KnowledgeGraphRestController controller;

    // ── helpers ────────────────────────────────────────────────────────────────

    private static dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse emptyProtoSearchResponse() {
        return dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse.newBuilder()
                .setSuccess(true)
                .setCreatedAt(System.currentTimeMillis())
                .build();
    }

    private static dev.ikm.tinkar.service.proto.TinkarConceptEntityResponse emptyProtoEntityResponse() {
        return dev.ikm.tinkar.service.proto.TinkarConceptEntityResponse.newBuilder().build();
    }

    // ── inspectConcept ─────────────────────────────────────────────────────────

    @Test
    void inspectConcept_allNullCoords_callsDefaultCalculatorAndReturns200() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            ConceptSemanticsResponse mockResponse = Mockito.mock(ConceptSemanticsResponse.class);
            when(tinkarService.inspectConcept(eq("concept-id"), same(mockCalc))).thenReturn(mockResponse);

            ResponseEntity<ConceptSemanticsResponse> response =
                    controller.inspectConcept("concept-id", null, null, null, null, null, null, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isSameAs(mockResponse);
        }
    }

    @Test
    void inspectConcept_delegatesConceptIdToService() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            ConceptSemanticsResponse mockResponse = Mockito.mock(ConceptSemanticsResponse.class);
            when(tinkarService.inspectConcept(eq("my-concept"), same(mockCalc))).thenReturn(mockResponse);

            controller.inspectConcept("my-concept", null, null, null, null, null, null, null, null);

            verify(tinkarService).inspectConcept(eq("my-concept"), same(mockCalc));
        }
    }

    // ── getConceptComments ─────────────────────────────────────────────────────

    @Test
    void getConceptComments_allNullCoords_callsDefaultCalculatorAndReturns200() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            ConceptSemanticsResponse mockResponse = Mockito.mock(ConceptSemanticsResponse.class);
            when(tinkarService.getConceptComments(eq("concept-id"), same(mockCalc))).thenReturn(mockResponse);

            ResponseEntity<ConceptSemanticsResponse> response =
                    controller.getConceptComments("concept-id", null, null, null, null, null, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isSameAs(mockResponse);
        }
    }

    // ── getChangeHistory ───────────────────────────────────────────────────────

    @Test
    void getChangeHistory_allNullCoords_callsDefaultCalculatorAndReturns200() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            ChangeHistoryResponse mockResponse = Mockito.mock(ChangeHistoryResponse.class);
            when(tinkarService.getChangeHistory(eq("entity-id"), same(mockCalc))).thenReturn(mockResponse);

            ResponseEntity<ChangeHistoryResponse> response =
                    controller.getChangeHistory("entity-id", null, null, null, null, null, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isSameAs(mockResponse);
        }
    }

    @Test
    void getChangeHistory_delegatesEntityIdToService() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            ChangeHistoryResponse mockResponse = Mockito.mock(ChangeHistoryResponse.class);
            when(tinkarService.getChangeHistory(eq("entity-xyz"), same(mockCalc))).thenReturn(mockResponse);

            controller.getChangeHistory("entity-xyz", null, null, null, null, null, null, null);

            verify(tinkarService).getChangeHistory(eq("entity-xyz"), same(mockCalc));
        }
    }

    // ── getConceptChangeHistory ────────────────────────────────────────────────

    @Test
    void getConceptChangeHistory_allNullCoords_callsDefaultCalculatorAndReturns200() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            ConceptChangeHistoryResponse mockResponse = Mockito.mock(ConceptChangeHistoryResponse.class);
            when(tinkarService.getConceptChangeHistory(eq("concept-id"), same(mockCalc))).thenReturn(mockResponse);

            ResponseEntity<ConceptChangeHistoryResponse> response =
                    controller.getConceptChangeHistory("concept-id", null, null, null, null, null, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isSameAs(mockResponse);
        }
    }

    // ── getChildConcepts ───────────────────────────────────────────────────────

    @Test
    void getChildConcepts_allNullCoords_callsDefaultCalculatorAndReturns200() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            when(tinkarService.getChildConcepts(eq("concept-id"), same(mockCalc)))
                    .thenReturn(emptyProtoSearchResponse());

            ResponseEntity<TinkarSearchQueryResponse> response =
                    controller.getChildConcepts("concept-id", null, null, null, null, null, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
        }
    }

    @Test
    void getChildConcepts_delegatesConceptIdToService() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            when(tinkarService.getChildConcepts(eq("parent-id"), same(mockCalc)))
                    .thenReturn(emptyProtoSearchResponse());

            controller.getChildConcepts("parent-id", null, null, null, null, null, null, null);

            verify(tinkarService).getChildConcepts(eq("parent-id"), same(mockCalc));
        }
    }

    // ── getDescendantConcepts ──────────────────────────────────────────────────

    @Test
    void getDescendantConcepts_allNullCoords_callsDefaultCalculatorAndReturns200() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            when(tinkarService.getDescendantConcepts(eq("concept-id"), same(mockCalc)))
                    .thenReturn(emptyProtoSearchResponse());

            ResponseEntity<TinkarSearchQueryResponse> response =
                    controller.getDescendantConcepts("concept-id", null, null, null, null, null, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
        }
    }

    @Test
    void getDescendantConcepts_delegatesConceptIdToService() {
        try (MockedStatic<CoordinateFactory> cfMock = Mockito.mockStatic(CoordinateFactory.class)) {
            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            cfMock.when(CoordinateFactory::defaultCalculator).thenReturn(mockCalc);

            when(tinkarService.getDescendantConcepts(eq("ancestor-id"), same(mockCalc)))
                    .thenReturn(emptyProtoSearchResponse());

            controller.getDescendantConcepts("ancestor-id", null, null, null, null, null, null, null);

            verify(tinkarService).getDescendantConcepts(eq("ancestor-id"), same(mockCalc));
        }
    }

    // ── createSampleChange ─────────────────────────────────────────────────────

    @Test
    void createSampleChange_delegatesToServiceAndReturns200() {
        ChangeHistoryResponse mockResponse = Mockito.mock(ChangeHistoryResponse.class);
        when(tinkarService.createSampleChange("concept-id", "A comment")).thenReturn(mockResponse);

        ResponseEntity<ChangeHistoryResponse> response =
                controller.createSampleChange("concept-id", "A comment");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(mockResponse);
        verify(tinkarService).createSampleChange("concept-id", "A comment");
    }

    // ── saveChanges ────────────────────────────────────────────────────────────

    @Test
    void saveChanges_delegatesToServiceAndReturns200() {
        when(tinkarService.saveChanges()).thenReturn("Saved OK");

        ResponseEntity<String> response = controller.saveChanges();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("Saved OK");
        verify(tinkarService).saveChanges();
    }

    // ── discardChanges ─────────────────────────────────────────────────────────

    @Test
    void discardChanges_delegatesToServiceAndReturns200() {
        when(tinkarService.discardChanges()).thenReturn("Discarded OK");

        ResponseEntity<String> response = controller.discardChanges();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("Discarded OK");
        verify(tinkarService).discardChanges();
    }

    // ── addDescendant ──────────────────────────────────────────────────────────

    @Test
    void addDescendant_delegatesToServiceAndReturns200() {
        DescendantOperationResponse mockResponse = Mockito.mock(DescendantOperationResponse.class);
        when(tinkarService.addDescendant("parent-id", "child-id")).thenReturn(mockResponse);

        ResponseEntity<DescendantOperationResponse> response =
                controller.addDescendant("parent-id", "child-id");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(mockResponse);
        verify(tinkarService).addDescendant("parent-id", "child-id");
    }

    // ── createAndAddDescendant ─────────────────────────────────────────────────

    @Test
    void createAndAddDescendant_delegatesToServiceAndReturns200() {
        DescendantOperationResponse mockResponse = Mockito.mock(DescendantOperationResponse.class);
        when(tinkarService.createAndAddDescendant("parent-id", "New Concept")).thenReturn(mockResponse);

        ResponseEntity<DescendantOperationResponse> response =
                controller.createAndAddDescendant("parent-id", "New Concept");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(mockResponse);
        verify(tinkarService).createAndAddDescendant("parent-id", "New Concept");
    }

    // ── removeDescendant ───────────────────────────────────────────────────────

    @Test
    void removeDescendant_delegatesToServiceAndReturns200() {
        DescendantOperationResponse mockResponse = Mockito.mock(DescendantOperationResponse.class);
        when(tinkarService.removeDescendant("parent-id", "child-id")).thenReturn(mockResponse);

        ResponseEntity<DescendantOperationResponse> response =
                controller.removeDescendant("parent-id", "child-id");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(mockResponse);
        verify(tinkarService).removeDescendant("parent-id", "child-id");
    }

    // ── saveStampCoordinate ────────────────────────────────────────────────────

    @Test
    void saveStampCoordinate_delegatesToStoreAndReturns201() {
        StampCoordinateDto dto = new StampCoordinateDto(null, null, null, null, null, null);
        SavedStampCoordinateResponse mockSaved = new SavedStampCoordinateResponse(
                "stamp-uuid", dto, "2024-01-01T00:00:00Z");
        when(coordinateStoreService.saveStamp(dto)).thenReturn(mockSaved);

        ResponseEntity<SavedStampCoordinateResponse> response =
                controller.saveStampCoordinate(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(mockSaved);
        verify(coordinateStoreService).saveStamp(dto);
    }

    @Test
    void saveStampCoordinate_withSettings_passesDtoToStore() {
        StampCoordinateDto dto = new StampCoordinateDto("ACTIVE", 1000L, "path-uuid",
                List.of("mod1"), null, null);
        SavedStampCoordinateResponse mockSaved = new SavedStampCoordinateResponse(
                "derived-uuid", dto, "2024-06-01T00:00:00Z");
        when(coordinateStoreService.saveStamp(dto)).thenReturn(mockSaved);

        ResponseEntity<SavedStampCoordinateResponse> response =
                controller.saveStampCoordinate(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo("derived-uuid");
        verify(coordinateStoreService).saveStamp(dto);
    }

    // ── listStampCoordinates ───────────────────────────────────────────────────

    @Test
    void listStampCoordinates_returnsListFromStoreWith200() {
        SavedStampCoordinateResponse entry = new SavedStampCoordinateResponse(
                "id-1", null, "2024-01-01T00:00:00Z");
        when(coordinateStoreService.findAllStamp()).thenReturn(List.of(entry));

        ResponseEntity<List<SavedStampCoordinateResponse>> response =
                controller.listStampCoordinates();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).id()).isEqualTo("id-1");
    }

    @Test
    void listStampCoordinates_emptyList_returnsEmptyBody() {
        when(coordinateStoreService.findAllStamp()).thenReturn(List.of());

        ResponseEntity<List<SavedStampCoordinateResponse>> response =
                controller.listStampCoordinates();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }

    // ── saveNavigationCoordinate ───────────────────────────────────────────────

    @Test
    void saveNavigationCoordinate_delegatesToStoreAndReturns201() {
        NavigationCoordinateDto dto = new NavigationCoordinateDto(null);
        SavedNavigationCoordinateResponse mockSaved = new SavedNavigationCoordinateResponse(
                "nav-uuid", dto, "2024-01-01T00:00:00Z");
        when(coordinateStoreService.saveNavigation(dto)).thenReturn(mockSaved);

        ResponseEntity<SavedNavigationCoordinateResponse> response =
                controller.saveNavigationCoordinate(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(mockSaved);
        verify(coordinateStoreService).saveNavigation(dto);
    }

    // ── listNavigationCoordinates ──────────────────────────────────────────────

    @Test
    void listNavigationCoordinates_returnsListFromStoreWith200() {
        SavedNavigationCoordinateResponse entry = new SavedNavigationCoordinateResponse(
                "nav-id-1", null, "2024-01-01T00:00:00Z");
        when(coordinateStoreService.findAllNavigation()).thenReturn(List.of(entry));

        ResponseEntity<List<SavedNavigationCoordinateResponse>> response =
                controller.listNavigationCoordinates();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).id()).isEqualTo("nav-id-1");
    }

    // ── saveLanguageCoordinate ─────────────────────────────────────────────────

    @Test
    void saveLanguageCoordinate_delegatesToStoreAndReturns201() {
        LanguageCoordinateDto dto = new LanguageCoordinateDto(null);
        SavedLanguageCoordinateResponse mockSaved = new SavedLanguageCoordinateResponse(
                "lang-uuid", dto, "2024-01-01T00:00:00Z");
        when(coordinateStoreService.saveLanguage(dto)).thenReturn(mockSaved);

        ResponseEntity<SavedLanguageCoordinateResponse> response =
                controller.saveLanguageCoordinate(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(mockSaved);
        verify(coordinateStoreService).saveLanguage(dto);
    }

    // ── listLanguageCoordinates ────────────────────────────────────────────────

    @Test
    void listLanguageCoordinates_returnsListFromStoreWith200() {
        SavedLanguageCoordinateResponse entry = new SavedLanguageCoordinateResponse(
                "lang-id-1", null, "2024-01-01T00:00:00Z");
        when(coordinateStoreService.findAllLanguage()).thenReturn(List.of(entry));

        ResponseEntity<List<SavedLanguageCoordinateResponse>> response =
                controller.listLanguageCoordinates();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).id()).isEqualTo("lang-id-1");
    }

    @Test
    void listLanguageCoordinates_emptyList_returnsEmptyBody() {
        when(coordinateStoreService.findAllLanguage()).thenReturn(List.of());

        ResponseEntity<List<SavedLanguageCoordinateResponse>> response =
                controller.listLanguageCoordinates();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }

    // ── getSemanticsWithCoordinate ─────────────────────────────────────────────

    @Test
    void getSemanticsWithCoordinate_nullIds_buildsCalculatorFromNullCoordinatesAndReturns200() {
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

            ConceptSemanticsResponse mockResponse = Mockito.mock(ConceptSemanticsResponse.class);
            when(tinkarService.inspectConcept(eq("concept-id"), same(mockCalc))).thenReturn(mockResponse);

            ResponseEntity<ConceptSemanticsResponse> response =
                    controller.getSemanticsWithCoordinate("concept-id", null, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isSameAs(mockResponse);
        }
    }

    // ── loadConceptEntityGraph ─────────────────────────────────────────────────

    @Test
    void loadConceptEntityGraph_delegatesToServiceAndReturnsByteArray() {
        when(tinkarService.loadConceptEntityGraph("concept-id"))
                .thenReturn(emptyProtoEntityResponse());

        ResponseEntity<byte[]> response = controller.loadConceptEntityGraph("concept-id");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getHeaders().getFirst("Content-Type"))
                .isEqualTo("application/x-protobuf");
        verify(tinkarService).loadConceptEntityGraph("concept-id");
    }

    // ── getEntityByPublicId ────────────────────────────────────────────────────

    @Test
    void getEntityByPublicId_delegatesToServiceAndReturnsByteArray() {
        when(tinkarService.getEntityByPublicId("entity-id"))
                .thenReturn(emptyProtoEntityResponse());

        ResponseEntity<byte[]> response = controller.getEntityByPublicId("entity-id");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getHeaders().getFirst("Content-Type"))
                .isEqualTo("application/x-protobuf");
        verify(tinkarService).getEntityByPublicId("entity-id");
    }

    @Test
    void getEntityByPublicId_returnsProtoBytes() {
        dev.ikm.tinkar.service.proto.TinkarConceptEntityResponse protoResponse =
                emptyProtoEntityResponse();
        when(tinkarService.getEntityByPublicId("entity-id")).thenReturn(protoResponse);

        ResponseEntity<byte[]> response = controller.getEntityByPublicId("entity-id");

        assertThat(response.getBody()).isEqualTo(protoResponse.toByteArray());
    }
}
