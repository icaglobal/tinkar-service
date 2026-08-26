package dev.ikm.tinkar.service.controller.admin;

import com.google.protobuf.ByteString;
import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.service.proto.ImportChangesetRequest;
import dev.ikm.tinkar.service.proto.ImportChangesetResponse;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import dev.ikm.tinkar.service.proto.RunReasonerEvent;
import dev.ikm.tinkar.service.proto.RunReasonerResult;
import dev.ikm.tinkar.service.service.ReasonerPhaseListener;
import org.eclipse.collections.api.factory.primitive.IntLists;
import org.eclipse.collections.api.factory.Sets;
import java.util.List;
import dev.ikm.tinkar.service.proto.RunReasonerRequest;
import dev.ikm.tinkar.service.service.TinkarService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminGrpcControllerTest {

    @Mock
    private TinkarService tinkarService;

    @InjectMocks
    private AdminGrpcController controller;

    // -------------------------------------------------------------------------
    // importChangeset
    // -------------------------------------------------------------------------

    @Test
    void importChangeset_successPath_callsOnNextAndOnCompleted() throws Exception {
        // Arrange
        ImportChangesetRequest request = ImportChangesetRequest.newBuilder()
                .setChangesetData(ByteString.EMPTY)
                .setUseMultiPass(true)
                .build();

        EntityCountSummaryResponse mockResult = Mockito.mock(EntityCountSummaryResponse.class);
        when(mockResult.success()).thenReturn(true);
        when(mockResult.errorMessage()).thenReturn(null);
        when(mockResult.conceptsCount()).thenReturn(10L);
        when(mockResult.semanticsCount()).thenReturn(20L);
        when(mockResult.patternsCount()).thenReturn(5L);
        when(mockResult.stampsCount()).thenReturn(3L);
        when(mockResult.totalCount()).thenReturn(38L);

        when(tinkarService.importChangeset(any(File.class), anyBoolean())).thenReturn(mockResult);

        @SuppressWarnings("unchecked")
        StreamObserver<ImportChangesetResponse> responseObserver = Mockito.mock(StreamObserver.class);

        // Act
        controller.importChangeset(request, responseObserver);

        // Assert — response sent and stream completed
        ArgumentCaptor<ImportChangesetResponse> captor =
                ArgumentCaptor.forClass(ImportChangesetResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        ImportChangesetResponse sent = captor.getValue();
        assertThat(sent.getSuccess()).isTrue();
        assertThat(sent.getEntityCounts().getConceptsCount()).isEqualTo(10);
        assertThat(sent.getEntityCounts().getSemanticsCount()).isEqualTo(20);
        assertThat(sent.getEntityCounts().getPatternsCount()).isEqualTo(5);
        assertThat(sent.getEntityCounts().getStampsCount()).isEqualTo(3);
        assertThat(sent.getEntityCounts().getTotalCount()).isEqualTo(38);
    }

    @Test
    void importChangeset_failurePath_setsSuccessFalseAndNoEntityCounts() throws Exception {
        // Arrange
        ImportChangesetRequest request = ImportChangesetRequest.newBuilder()
                .setChangesetData(ByteString.EMPTY)
                .setUseMultiPass(false)
                .build();

        EntityCountSummaryResponse mockResult = Mockito.mock(EntityCountSummaryResponse.class);
        when(mockResult.success()).thenReturn(false);
        when(mockResult.errorMessage()).thenReturn("import failed");

        when(tinkarService.importChangeset(any(File.class), anyBoolean())).thenReturn(mockResult);

        @SuppressWarnings("unchecked")
        StreamObserver<ImportChangesetResponse> responseObserver = Mockito.mock(StreamObserver.class);

        // Act
        controller.importChangeset(request, responseObserver);

        // Assert
        ArgumentCaptor<ImportChangesetResponse> captor =
                ArgumentCaptor.forClass(ImportChangesetResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        ImportChangesetResponse sent = captor.getValue();
        assertThat(sent.getSuccess()).isFalse();
        assertThat(sent.getErrorMessage()).isEqualTo("import failed");
        assertThat(sent.hasEntityCounts()).isFalse();
    }

    @Test
    void importChangeset_whenServiceThrows_respondsWithErrorAndCompletes() throws Exception {
        // Arrange
        ImportChangesetRequest request = ImportChangesetRequest.newBuilder()
                .setChangesetData(ByteString.EMPTY)
                .setUseMultiPass(true)
                .build();

        when(tinkarService.importChangeset(any(File.class), anyBoolean()))
                .thenThrow(new RuntimeException("unexpected failure"));

        @SuppressWarnings("unchecked")
        StreamObserver<ImportChangesetResponse> responseObserver = Mockito.mock(StreamObserver.class);

        // Act
        controller.importChangeset(request, responseObserver);

        // Assert — controller catches exception, sends error response, and completes stream
        ArgumentCaptor<ImportChangesetResponse> captor =
                ArgumentCaptor.forClass(ImportChangesetResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        ImportChangesetResponse sent = captor.getValue();
        assertThat(sent.getSuccess()).isFalse();
        assertThat(sent.getErrorMessage()).contains("unexpected failure");
    }

    // -------------------------------------------------------------------------
    // runReasoner
    // -------------------------------------------------------------------------

    @Test
    void runReasoner_streamsEachPhaseThenTheResult() throws Exception {
        ClassifierResults results = Mockito.mock(ClassifierResults.class);
        when(results.getClassificationConceptSet()).thenReturn(IntLists.immutable.empty());
        when(results.getConceptsWithInferredChanges()).thenReturn(IntLists.immutable.empty());
        when(results.getConceptsWithNavigationChanges()).thenReturn(IntLists.immutable.empty());
        when(results.getEquivalentSets()).thenReturn(Sets.immutable.empty());
        when(results.getCycles()).thenReturn(null);
        when(results.getOrphans()).thenReturn(null);
        when(results.getViewCoordinate()).thenReturn(null);

        // Drive the listener the controller passes in, so the phases it forwards are the ones
        // the pipeline would really report.
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class))).thenAnswer(invocation -> {
            ReasonerPhaseListener listener = invocation.getArgument(0);
            listener.onPhaseComplete(1, 4, "Loading data into reasoner");
            listener.onPhaseComplete(2, 4, "Computing inferences");
            listener.onPhaseComplete(3, 4, "Building necessary normal form");
            listener.onPhaseComplete(4, 4, "Processing results");
            return results;
        });

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> responseObserver = Mockito.mock(StreamObserver.class);

        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), responseObserver);

        ArgumentCaptor<RunReasonerEvent> captor = ArgumentCaptor.forClass(RunReasonerEvent.class);
        verify(responseObserver, Mockito.times(5)).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        List<RunReasonerEvent> events = captor.getAllValues();

        // Four phases, in order, numbered to match Komet's local pipeline.
        assertThat(events.subList(0, 4)).allMatch(RunReasonerEvent::hasPhase);
        assertThat(events.subList(0, 4))
                .extracting(e -> e.getPhase().getStep())
                .containsExactly(1, 2, 3, 4);
        assertThat(events.get(2).getPhase().getMessage())
                .isEqualTo("Building necessary normal form");
        assertThat(events.get(0).getPhase().getTotalSteps()).isEqualTo(4);

        // Then exactly one result, terminal.
        assertThat(events.get(4).hasResult()).isTrue();
        assertThat(events.get(4).getResult().getSuccess()).isTrue();
    }

    @Test
    void runReasoner_doesNotSendTheClassificationConceptSet() throws Exception {
        // The panel only reads its size, so the concepts are deliberately omitted; sending them
        // put the response over gRPC's default message limit.
        ClassifierResults results = Mockito.mock(ClassifierResults.class);
        when(results.getClassificationConceptSet()).thenReturn(IntLists.immutable.of(1, 2, 3));
        when(results.getConceptsWithInferredChanges()).thenReturn(IntLists.immutable.empty());
        when(results.getConceptsWithNavigationChanges()).thenReturn(IntLists.immutable.empty());
        when(results.getEquivalentSets()).thenReturn(Sets.immutable.empty());
        when(results.getCycles()).thenReturn(null);
        when(results.getOrphans()).thenReturn(null);
        when(results.getViewCoordinate()).thenReturn(null);
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class))).thenReturn(results);

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> responseObserver = Mockito.mock(StreamObserver.class);

        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), responseObserver);

        ArgumentCaptor<RunReasonerEvent> captor = ArgumentCaptor.forClass(RunReasonerEvent.class);
        verify(responseObserver).onNext(captor.capture());

        RunReasonerResult result = captor.getValue().getResult();
        assertThat(result.getCounts().getClassifiedConceptCount()).isEqualTo(3);
    }

    @Test
    void runReasoner_failureIsReportedAsAResultNotAStreamError() throws Exception {
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class)))
                .thenThrow(new IllegalStateException("reasoner timed out"));

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> responseObserver = Mockito.mock(StreamObserver.class);

        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), responseObserver);

        ArgumentCaptor<RunReasonerEvent> captor = ArgumentCaptor.forClass(RunReasonerEvent.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, Mockito.never()).onError(any());

        RunReasonerResult result = captor.getValue().getResult();
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("reasoner timed out");
    }
}
