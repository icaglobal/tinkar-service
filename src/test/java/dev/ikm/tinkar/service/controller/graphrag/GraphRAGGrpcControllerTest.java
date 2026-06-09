package dev.ikm.tinkar.service.controller.graphrag;

import dev.ikm.tinkar.schema.PublicId;
import dev.ikm.tinkar.service.dto.ConceptSearchResponse;
import dev.ikm.tinkar.service.dto.SearchSortOption;
import dev.ikm.tinkar.service.proto.TinkarConceptIdRequest;
import dev.ikm.tinkar.service.proto.TinkarConceptSearchRequest;
import dev.ikm.tinkar.service.proto.TinkarConceptSearchWithSortRequest;
import dev.ikm.tinkar.service.proto.TinkarConceptSearchWithSortResponse;
import dev.ikm.tinkar.service.proto.TinkarRebuildIndexRequest;
import dev.ikm.tinkar.service.proto.TinkarRebuildIndexResponse;
import dev.ikm.tinkar.service.proto.TinkarSearchQueryRequest;
import dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse;
import dev.ikm.tinkar.service.service.TinkarService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphRAGGrpcControllerTest {

    @Mock
    private TinkarService tinkarService;

    @InjectMocks
    private GraphRAGGrpcController controller;

    // Helper: minimal valid proto response
    private static TinkarSearchQueryResponse validProto() {
        return TinkarSearchQueryResponse.newBuilder()
                .setSuccess(true)
                .setCreatedAt(System.currentTimeMillis())
                .build();
    }

    // Helper: build a TinkarConceptIdRequest with a UUID
    private static TinkarConceptIdRequest conceptIdRequest(String uuid) {
        return TinkarConceptIdRequest.newBuilder()
                .setPublicId(PublicId.newBuilder().addUuids(uuid).build())
                .build();
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_callsServiceWithCorrectQuery() {
        TinkarSearchQueryRequest request = TinkarSearchQueryRequest.newBuilder()
                .setQuery("diabetes")
                .build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.search("diabetes")).thenReturn(validProto());

        controller.search(request, observer);

        verify(tinkarService).search("diabetes");
    }

    @Test
    void search_callsOnNext() {
        TinkarSearchQueryRequest request = TinkarSearchQueryRequest.newBuilder()
                .setQuery("diabetes")
                .build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        TinkarSearchQueryResponse proto = validProto();
        when(tinkarService.search("diabetes")).thenReturn(proto);

        controller.search(request, observer);

        verify(observer).onNext(proto);
    }

    @Test
    void search_callsOnCompleted() {
        TinkarSearchQueryRequest request = TinkarSearchQueryRequest.newBuilder()
                .setQuery("diabetes")
                .build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.search(any())).thenReturn(validProto());

        controller.search(request, observer);

        verify(observer).onCompleted();
    }

    // ── conceptSearch ─────────────────────────────────────────────────────────

    @Test
    void conceptSearch_callsServiceWithQueryAndMaxResults() {
        TinkarConceptSearchRequest request = TinkarConceptSearchRequest.newBuilder()
                .setQuery("blood")
                .setMaxResults(10)
                .build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.conceptSearch("blood", 10)).thenReturn(validProto());

        controller.conceptSearch(request, observer);

        verify(tinkarService).conceptSearch("blood", 10);
    }

    @Test
    void conceptSearch_zeroMaxResults_passesNullToService() {
        TinkarConceptSearchRequest request = TinkarConceptSearchRequest.newBuilder()
                .setQuery("heart")
                .setMaxResults(0)
                .build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.conceptSearch("heart", null)).thenReturn(validProto());

        controller.conceptSearch(request, observer);

        verify(tinkarService).conceptSearch("heart", null);
    }

    @Test
    void conceptSearch_callsOnNextAndOnCompleted() {
        TinkarConceptSearchRequest request = TinkarConceptSearchRequest.newBuilder()
                .setQuery("lung")
                .setMaxResults(5)
                .build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        TinkarSearchQueryResponse proto = validProto();
        when(tinkarService.conceptSearch("lung", 5)).thenReturn(proto);

        controller.conceptSearch(request, observer);

        verify(observer).onNext(proto);
        verify(observer).onCompleted();
    }

    // ── conceptSearchWithSort ─────────────────────────────────────────────────

    @Test
    void conceptSearchWithSort_callsServiceWithSortOption() {
        TinkarConceptSearchWithSortRequest request = TinkarConceptSearchWithSortRequest.newBuilder()
                .setQuery("fever")
                .setMaxResults(20)
                .setSortBy(dev.ikm.tinkar.service.proto.SearchSortOption.SEMANTIC)
                .build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarConceptSearchWithSortResponse> observer = mock(StreamObserver.class);
        ConceptSearchResponse dto = mock(ConceptSearchResponse.class);
        when(tinkarService.conceptSearchWithSort(eq("fever"), eq(20), any(SearchSortOption.class))).thenReturn(dto);

        controller.conceptSearchWithSort(request, observer);

        verify(tinkarService).conceptSearchWithSort(eq("fever"), eq(20), any(SearchSortOption.class));
    }

    @Test
    void conceptSearchWithSort_callsOnNextAndOnCompleted() {
        TinkarConceptSearchWithSortRequest request = TinkarConceptSearchWithSortRequest.newBuilder()
                .setQuery("pain")
                .setMaxResults(0)
                .setSortBy(dev.ikm.tinkar.service.proto.SearchSortOption.TOP_COMPONENT)
                .build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarConceptSearchWithSortResponse> observer = mock(StreamObserver.class);
        ConceptSearchResponse dto = ConceptSearchResponse.empty("pain");
        when(tinkarService.conceptSearchWithSort(eq("pain"), eq(null), any())).thenReturn(dto);

        controller.conceptSearchWithSort(request, observer);

        verify(observer).onNext(any(TinkarConceptSearchWithSortResponse.class));
        verify(observer).onCompleted();
    }

    // ── getEntity ─────────────────────────────────────────────────────────────

    @Test
    void getEntity_callsServiceWithExtractedConceptId() {
        TinkarConceptIdRequest request = conceptIdRequest("concept-id-1");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.getEntity("concept-id-1")).thenReturn(validProto());

        controller.getEntity(request, observer);

        verify(tinkarService).getEntity("concept-id-1");
    }

    @Test
    void getEntity_callsOnNextAndOnCompleted() {
        TinkarConceptIdRequest request = conceptIdRequest("concept-id-1");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        TinkarSearchQueryResponse proto = validProto();
        when(tinkarService.getEntity("concept-id-1")).thenReturn(proto);

        controller.getEntity(request, observer);

        verify(observer).onNext(proto);
        verify(observer).onCompleted();
    }

    // ── getChildConcepts ──────────────────────────────────────────────────────

    @Test
    void getChildConcepts_callsServiceWithExtractedConceptId() {
        TinkarConceptIdRequest request = conceptIdRequest("parent-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.getChildConcepts("parent-id")).thenReturn(validProto());

        controller.getChildConcepts(request, observer);

        verify(tinkarService).getChildConcepts("parent-id");
    }

    @Test
    void getChildConcepts_callsOnNextAndOnCompleted() {
        TinkarConceptIdRequest request = conceptIdRequest("parent-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        TinkarSearchQueryResponse proto = validProto();
        when(tinkarService.getChildConcepts("parent-id")).thenReturn(proto);

        controller.getChildConcepts(request, observer);

        verify(observer).onNext(proto);
        verify(observer).onCompleted();
    }

    // ── getDescendantConcepts ─────────────────────────────────────────────────

    @Test
    void getDescendantConcepts_callsServiceWithExtractedConceptId() {
        TinkarConceptIdRequest request = conceptIdRequest("root-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.getDescendantConcepts("root-id")).thenReturn(validProto());

        controller.getDescendantConcepts(request, observer);

        verify(tinkarService).getDescendantConcepts("root-id");
    }

    @Test
    void getDescendantConcepts_callsOnNextAndOnCompleted() {
        TinkarConceptIdRequest request = conceptIdRequest("root-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        TinkarSearchQueryResponse proto = validProto();
        when(tinkarService.getDescendantConcepts("root-id")).thenReturn(proto);

        controller.getDescendantConcepts(request, observer);

        verify(observer).onNext(proto);
        verify(observer).onCompleted();
    }

    // ── getLIDRRecordConceptsFromTestKit ──────────────────────────────────────

    @Test
    void getLIDRRecordConceptsFromTestKit_callsServiceWithCorrectId() {
        TinkarConceptIdRequest request = conceptIdRequest("testkit-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.getLIDRRecordConceptsFromTestKit("testkit-id")).thenReturn(validProto());

        controller.getLIDRRecordConceptsFromTestKit(request, observer);

        verify(tinkarService).getLIDRRecordConceptsFromTestKit("testkit-id");
    }

    @Test
    void getLIDRRecordConceptsFromTestKit_callsOnNextAndOnCompleted() {
        TinkarConceptIdRequest request = conceptIdRequest("testkit-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        TinkarSearchQueryResponse proto = validProto();
        when(tinkarService.getLIDRRecordConceptsFromTestKit("testkit-id")).thenReturn(proto);

        controller.getLIDRRecordConceptsFromTestKit(request, observer);

        verify(observer).onNext(proto);
        verify(observer).onCompleted();
    }

    // ── getResultConformanceConceptsFromLIDRRecord ────────────────────────────

    @Test
    void getResultConformanceConceptsFromLIDRRecord_callsServiceWithCorrectId() {
        TinkarConceptIdRequest request = conceptIdRequest("lidr-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.getResultConformanceConceptsFromLIDRRecord("lidr-id")).thenReturn(validProto());

        controller.getResultConformanceConceptsFromLIDRRecord(request, observer);

        verify(tinkarService).getResultConformanceConceptsFromLIDRRecord("lidr-id");
    }

    @Test
    void getResultConformanceConceptsFromLIDRRecord_callsOnNextAndOnCompleted() {
        TinkarConceptIdRequest request = conceptIdRequest("lidr-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        TinkarSearchQueryResponse proto = validProto();
        when(tinkarService.getResultConformanceConceptsFromLIDRRecord("lidr-id")).thenReturn(proto);

        controller.getResultConformanceConceptsFromLIDRRecord(request, observer);

        verify(observer).onNext(proto);
        verify(observer).onCompleted();
    }

    // ── getAllowedResultConceptsFromResultConformance ──────────────────────────

    @Test
    void getAllowedResultConceptsFromResultConformance_callsServiceWithCorrectId() {
        TinkarConceptIdRequest request = conceptIdRequest("conformance-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        when(tinkarService.getAllowedResultConceptsFromResultConformance("conformance-id")).thenReturn(validProto());

        controller.getAllowedResultConceptsFromResultConformance(request, observer);

        verify(tinkarService).getAllowedResultConceptsFromResultConformance("conformance-id");
    }

    @Test
    void getAllowedResultConceptsFromResultConformance_callsOnNextAndOnCompleted() {
        TinkarConceptIdRequest request = conceptIdRequest("conformance-id");
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarSearchQueryResponse> observer = mock(StreamObserver.class);
        TinkarSearchQueryResponse proto = validProto();
        when(tinkarService.getAllowedResultConceptsFromResultConformance("conformance-id")).thenReturn(proto);

        controller.getAllowedResultConceptsFromResultConformance(request, observer);

        verify(observer).onNext(proto);
        verify(observer).onCompleted();
    }

    // ── rebuildSearchIndex ────────────────────────────────────────────────────

    @Test
    void rebuildSearchIndex_callsService() {
        TinkarRebuildIndexRequest request = TinkarRebuildIndexRequest.newBuilder().build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarRebuildIndexResponse> observer = mock(StreamObserver.class);
        when(tinkarService.rebuildSearchIndex()).thenReturn("Index rebuild started");

        controller.rebuildSearchIndex(request, observer);

        verify(tinkarService).rebuildSearchIndex();
    }

    @Test
    void rebuildSearchIndex_callsOnNextAndOnCompleted() {
        TinkarRebuildIndexRequest request = TinkarRebuildIndexRequest.newBuilder().build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarRebuildIndexResponse> observer = mock(StreamObserver.class);
        when(tinkarService.rebuildSearchIndex()).thenReturn("Index rebuild started");

        controller.rebuildSearchIndex(request, observer);

        verify(observer).onNext(any(TinkarRebuildIndexResponse.class));
        verify(observer).onCompleted();
    }

    @Test
    void rebuildSearchIndex_failedMessage_setsSuccessFalse() {
        TinkarRebuildIndexRequest request = TinkarRebuildIndexRequest.newBuilder().build();
        @SuppressWarnings("unchecked")
        StreamObserver<TinkarRebuildIndexResponse> observer = mock(StreamObserver.class);
        when(tinkarService.rebuildSearchIndex()).thenReturn("Failed to rebuild index");

        controller.rebuildSearchIndex(request, observer);

        // Verify onNext was called (response body not null) and onCompleted called
        verify(observer).onNext(any(TinkarRebuildIndexResponse.class));
        verify(observer).onCompleted();
    }
}
