package dev.ikm.tinkar.service.controller;

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

/**
 * Unit tests for the deprecated {@link TinkarSearchController}.
 * The controller is still functional; these tests verify the thin wrapper behaviour.
 */
@SuppressWarnings("deprecation")
@ExtendWith(MockitoExtension.class)
class TinkarSearchControllerTest {

    @Mock
    private TinkarService tinkarService;

    @InjectMocks
    private TinkarSearchController controller;

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
        when(tinkarService.search("chronic lung")).thenReturn(validProto());

        controller.search("chronic lung");

        verify(tinkarService).search("chronic lung");
    }

    @Test
    void search_responseBodyIsNotNull() {
        when(tinkarService.search("blood")).thenReturn(validProto());

        assertThat(controller.search("blood").getBody()).isNotNull();
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
        when(tinkarService.getEntity("f5c39ec3-7256-3a03-b651-d17b623a30ec")).thenReturn(validProto());

        controller.getEntity("f5c39ec3-7256-3a03-b651-d17b623a30ec");

        verify(tinkarService).getEntity("f5c39ec3-7256-3a03-b651-d17b623a30ec");
    }

    @Test
    void getEntity_responseBodyIsNotNull() {
        when(tinkarService.getEntity("concept-id-2")).thenReturn(validProto());

        assertThat(controller.getEntity("concept-id-2").getBody()).isNotNull();
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

    // ── conceptSearch ─────────────────────────────────────────────────────────

    @Test
    void conceptSearch_returns200() {
        when(tinkarService.conceptSearch("heart", 10)).thenReturn(validProto());

        ResponseEntity<TinkarSearchQueryResponse> response = controller.conceptSearch("heart", 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void conceptSearch_callsServiceWithCorrectArgs() {
        when(tinkarService.conceptSearch("heart", 10)).thenReturn(validProto());

        controller.conceptSearch("heart", 10);

        verify(tinkarService).conceptSearch("heart", 10);
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
        when(tinkarService.conceptSearchWithSort("fever", 20, SearchSortOption.SEMANTIC_ALPHA)).thenReturn(dto);

        controller.conceptSearchWithSort("fever", 20, SearchSortOption.SEMANTIC_ALPHA);

        verify(tinkarService).conceptSearchWithSort("fever", 20, SearchSortOption.SEMANTIC_ALPHA);
    }

    @Test
    void conceptSearchWithSort_responseBodyIsNotNull() {
        ConceptSearchResponse dto = mock(ConceptSearchResponse.class);
        when(tinkarService.conceptSearchWithSort("pain", null, null)).thenReturn(dto);

        assertThat(controller.conceptSearchWithSort("pain", null, null).getBody()).isNotNull();
    }
}
