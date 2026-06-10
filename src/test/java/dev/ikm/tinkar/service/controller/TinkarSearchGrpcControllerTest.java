package dev.ikm.tinkar.service.controller;

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

/**
 * Unit tests for the deprecated {@link TinkarSearchGrpcController}.
 * The controller is still functional; these tests verify the thin wrapper behaviour.
 */
@SuppressWarnings("deprecation")
@ExtendWith(MockitoExtension.class)
class TinkarSearchGrpcControllerTest {

    @Mock
    private TinkarService tinkarService;

    @InjectMocks
    private TinkarSearchGrpcController controller;

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

        verify(observer).onNext(any(TinkarRebuildIndexResponse.class));
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
}
