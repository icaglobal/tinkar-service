package dev.ikm.tinkar.service.controller.graphrag;

import dev.ikm.tinkar.service.dto.ConceptSearchResponse;
import dev.ikm.tinkar.service.dto.SearchSortOption;
import dev.ikm.tinkar.service.dto.TinkarSearchQueryResponse;
import dev.ikm.tinkar.service.service.TinkarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphRAGRestControllerTest {

    @Mock
    private TinkarService tinkarService;

    @InjectMocks
    private GraphRAGRestController controller;

    // Helper: minimal valid proto that ProtoConversionUtils.toDto() can process without NPE
    private static dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse validProto() {
        return dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse.newBuilder()
                .setSuccess(true)
                .setCreatedAt(System.currentTimeMillis())
                .build();
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_returns200() {
        when(tinkarService.search("diabetes")).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response = controller.search("diabetes");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void search_callsServiceWithCorrectQuery() {
        when(tinkarService.search("diabetes")).thenReturn(validProto());

        controller.search("diabetes");

        verify(tinkarService).search("diabetes");
    }

    @Test
    void search_responseBodyIsNotNull() {
        when(tinkarService.search("chronic lung")).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response = controller.search("chronic lung");

        assertThat(response.getBody()).isNotNull();
    }

    // ── conceptSearch ─────────────────────────────────────────────────────────

    @Test
    void conceptSearch_returns200() {
        when(tinkarService.conceptSearch("blood", 10)).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response = controller.conceptSearch("blood", 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void conceptSearch_callsServiceWithCorrectArgs() {
        when(tinkarService.conceptSearch("blood", 10)).thenReturn(validProto());

        controller.conceptSearch("blood", 10);

        verify(tinkarService).conceptSearch("blood", 10);
    }

    @Test
    void conceptSearch_nullMaxResults_passedThrough() {
        when(tinkarService.conceptSearch("heart", null)).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response = controller.conceptSearch("heart", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(tinkarService).conceptSearch("heart", null);
    }

    @Test
    void conceptSearch_responseBodyIsNotNull() {
        when(tinkarService.conceptSearch("lung", 5)).thenReturn(validProto());

        assertThat(controller.conceptSearch("lung", 5).getBody()).isNotNull();
    }

    // ── conceptSearchWithSort ─────────────────────────────────────────────────

    @Test
    void conceptSearchWithSort_returns200() {
        ConceptSearchResponse dto = mock(ConceptSearchResponse.class);
        when(tinkarService.conceptSearchWithSort("fever", 20, SearchSortOption.TOP_COMPONENT)).thenReturn(dto);

        ResponseEntity<ConceptSearchResponse> response =
                controller.conceptSearchWithSort("fever", 20, SearchSortOption.TOP_COMPONENT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void conceptSearchWithSort_callsServiceWithCorrectArgs() {
        ConceptSearchResponse dto = mock(ConceptSearchResponse.class);
        when(tinkarService.conceptSearchWithSort("fever", 20, SearchSortOption.SEMANTIC)).thenReturn(dto);

        controller.conceptSearchWithSort("fever", 20, SearchSortOption.SEMANTIC);

        verify(tinkarService).conceptSearchWithSort("fever", 20, SearchSortOption.SEMANTIC);
    }

    @Test
    void conceptSearchWithSort_responseBodyIsNotNull() {
        ConceptSearchResponse dto = mock(ConceptSearchResponse.class);
        when(tinkarService.conceptSearchWithSort("pain", null, null)).thenReturn(dto);

        assertThat(controller.conceptSearchWithSort("pain", null, null).getBody()).isNotNull();
    }

    // ── getEntity ─────────────────────────────────────────────────────────────

    @Test
    void getEntity_returns200() {
        when(tinkarService.getEntity("concept-id-1")).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response = controller.getEntity("concept-id-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getEntity_callsServiceWithCorrectConceptId() {
        when(tinkarService.getEntity("concept-id-1")).thenReturn(validProto());

        controller.getEntity("concept-id-1");

        verify(tinkarService).getEntity("concept-id-1");
    }

    @Test
    void getEntity_responseBodyIsNotNull() {
        when(tinkarService.getEntity("concept-id-2")).thenReturn(validProto());

        assertThat(controller.getEntity("concept-id-2").getBody()).isNotNull();
    }

    // ── getChildConcepts ──────────────────────────────────────────────────────

    @Test
    void getChildConcepts_returns200() {
        when(tinkarService.getChildConcepts("parent-id")).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response = controller.getChildConcepts("parent-id");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getChildConcepts_callsServiceWithCorrectConceptId() {
        when(tinkarService.getChildConcepts("parent-id")).thenReturn(validProto());

        controller.getChildConcepts("parent-id");

        verify(tinkarService).getChildConcepts("parent-id");
    }

    @Test
    void getChildConcepts_responseBodyIsNotNull() {
        when(tinkarService.getChildConcepts("parent-id")).thenReturn(validProto());

        assertThat(controller.getChildConcepts("parent-id").getBody()).isNotNull();
    }

    // ── getDescendantConcepts ─────────────────────────────────────────────────

    @Test
    void getDescendantConcepts_returns200() {
        when(tinkarService.getDescendantConcepts("root-id")).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response = controller.getDescendantConcepts("root-id");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getDescendantConcepts_callsServiceWithCorrectConceptId() {
        when(tinkarService.getDescendantConcepts("root-id")).thenReturn(validProto());

        controller.getDescendantConcepts("root-id");

        verify(tinkarService).getDescendantConcepts("root-id");
    }

    @Test
    void getDescendantConcepts_responseBodyIsNotNull() {
        when(tinkarService.getDescendantConcepts("root-id")).thenReturn(validProto());

        assertThat(controller.getDescendantConcepts("root-id").getBody()).isNotNull();
    }

    // ── getLIDRRecordConceptsFromTestKit ──────────────────────────────────────

    @Test
    void getLIDRRecordConceptsFromTestKit_returns200() {
        when(tinkarService.getLIDRRecordConceptsFromTestKit("testkit-id")).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response =
                controller.getLIDRRecordConceptsFromTestKit("testkit-id");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getLIDRRecordConceptsFromTestKit_callsServiceWithCorrectId() {
        when(tinkarService.getLIDRRecordConceptsFromTestKit("testkit-id")).thenReturn(validProto());

        controller.getLIDRRecordConceptsFromTestKit("testkit-id");

        verify(tinkarService).getLIDRRecordConceptsFromTestKit("testkit-id");
    }

    @Test
    void getLIDRRecordConceptsFromTestKit_responseBodyIsNotNull() {
        when(tinkarService.getLIDRRecordConceptsFromTestKit("testkit-id")).thenReturn(validProto());

        assertThat(controller.getLIDRRecordConceptsFromTestKit("testkit-id").getBody()).isNotNull();
    }

    // ── getResultConformanceConceptsFromLIDRRecord ────────────────────────────

    @Test
    void getResultConformanceConceptsFromLIDRRecord_returns200() {
        when(tinkarService.getResultConformanceConceptsFromLIDRRecord("lidr-id")).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response =
                controller.getResultConformanceConceptsFromLIDRRecord("lidr-id");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getResultConformanceConceptsFromLIDRRecord_callsServiceWithCorrectId() {
        when(tinkarService.getResultConformanceConceptsFromLIDRRecord("lidr-id")).thenReturn(validProto());

        controller.getResultConformanceConceptsFromLIDRRecord("lidr-id");

        verify(tinkarService).getResultConformanceConceptsFromLIDRRecord("lidr-id");
    }

    @Test
    void getResultConformanceConceptsFromLIDRRecord_responseBodyIsNotNull() {
        when(tinkarService.getResultConformanceConceptsFromLIDRRecord("lidr-id")).thenReturn(validProto());

        assertThat(controller.getResultConformanceConceptsFromLIDRRecord("lidr-id").getBody()).isNotNull();
    }

    // ── getAllowedResultConceptsFromResultConformance ──────────────────────────

    @Test
    void getAllowedResultConceptsFromResultConformance_returns200() {
        when(tinkarService.getAllowedResultConceptsFromResultConformance("conformance-id")).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response =
                controller.getAllowedResultConceptsFromResultConformance("conformance-id");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getAllowedResultConceptsFromResultConformance_callsServiceWithCorrectId() {
        when(tinkarService.getAllowedResultConceptsFromResultConformance("conformance-id")).thenReturn(validProto());

        controller.getAllowedResultConceptsFromResultConformance("conformance-id");

        verify(tinkarService).getAllowedResultConceptsFromResultConformance("conformance-id");
    }

    @Test
    void getAllowedResultConceptsFromResultConformance_responseBodyIsNotNull() {
        when(tinkarService.getAllowedResultConceptsFromResultConformance("conformance-id")).thenReturn(validProto());

        assertThat(controller.getAllowedResultConceptsFromResultConformance("conformance-id").getBody()).isNotNull();
    }

    // ── rebuildSearchIndex ────────────────────────────────────────────────────

    @Test
    void rebuildSearchIndex_returns200() {
        when(tinkarService.rebuildSearchIndex()).thenReturn("Index rebuild started");

        ResponseEntity<String> response = controller.rebuildSearchIndex();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rebuildSearchIndex_callsService() {
        when(tinkarService.rebuildSearchIndex()).thenReturn("Index rebuild started");

        controller.rebuildSearchIndex();

        verify(tinkarService).rebuildSearchIndex();
    }

    @Test
    void rebuildSearchIndex_responseBodyIsNotNull() {
        when(tinkarService.rebuildSearchIndex()).thenReturn("Index rebuild started");

        assertThat(controller.rebuildSearchIndex().getBody()).isNotNull();
    }
}
