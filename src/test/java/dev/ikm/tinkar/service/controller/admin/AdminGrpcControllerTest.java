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
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import dev.ikm.tinkar.service.proto.CancelReasonerRequest;
import dev.ikm.tinkar.service.proto.CancelReasonerResponse;
import dev.ikm.tinkar.service.proto.WatchReasonerRequest;
import dev.ikm.tinkar.service.service.ReasonerRunManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import dev.ikm.tinkar.common.service.TrackingCallable;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminGrpcControllerTest {

    @Mock
    private TinkarService tinkarService;

    // Constructed rather than @InjectMocks: the run manager is real — it is what the reasoner
    // calls are about — over the mocked service.
    private ReasonerRunManager reasonerRuns;

    private AdminGrpcController controller;

    @BeforeEach
    void createController() {
        reasonerRuns = new ReasonerRunManager(tinkarService);
        controller = new AdminGrpcController(tinkarService, reasonerRuns);
    }

    /** Runs are asynchronous: events arrive on the run's thread, after the call has returned. */
    private static final int WAIT_MS = 5000;

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
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any())).thenAnswer(invocation -> {
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

        verify(responseObserver, timeout(WAIT_MS)).onCompleted();
        ArgumentCaptor<RunReasonerEvent> captor = ArgumentCaptor.forClass(RunReasonerEvent.class);
        verify(responseObserver, Mockito.times(6)).onNext(captor.capture());

        List<RunReasonerEvent> events = captor.getAllValues();

        // First, which run this is — one this call started.
        assertThat(events.get(0).hasRun()).isTrue();
        assertThat(events.get(0).getRun().getStarted()).isTrue();

        // Four phases, in order, numbered to match Komet's local pipeline.
        List<RunReasonerEvent> phases = events.subList(1, 5);
        assertThat(phases).allMatch(RunReasonerEvent::hasPhase);
        assertThat(phases)
                .extracting(e -> e.getPhase().getStep())
                .containsExactly(1, 2, 3, 4);
        assertThat(phases.get(2).getPhase().getMessage())
                .isEqualTo("Building necessary normal form");
        assertThat(phases.get(0).getPhase().getTotalSteps()).isEqualTo(4);

        // Then exactly one result, terminal.
        assertThat(events.get(5).hasResult()).isTrue();
        assertThat(events.get(5).getResult().getSuccess()).isTrue();
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
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any())).thenReturn(results);

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> responseObserver = Mockito.mock(StreamObserver.class);

        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), responseObserver);

        verify(responseObserver, timeout(WAIT_MS)).onCompleted();
        ArgumentCaptor<RunReasonerEvent> captor = ArgumentCaptor.forClass(RunReasonerEvent.class);
        verify(responseObserver, Mockito.times(2)).onNext(captor.capture());

        RunReasonerResult result = captor.getValue().getResult();
        assertThat(result.getCounts().getClassifiedConceptCount()).isEqualTo(3);
    }

    @Test
    void runReasoner_failureIsReportedAsAResultNotAStreamError() throws Exception {
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any()))
                .thenThrow(new IllegalStateException("reasoner timed out"));

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> responseObserver = Mockito.mock(StreamObserver.class);

        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), responseObserver);

        verify(responseObserver, timeout(WAIT_MS)).onCompleted();
        ArgumentCaptor<RunReasonerEvent> captor = ArgumentCaptor.forClass(RunReasonerEvent.class);
        verify(responseObserver, Mockito.times(2)).onNext(captor.capture());
        verify(responseObserver, Mockito.never()).onError(any());

        RunReasonerResult result = captor.getValue().getResult();
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("reasoner timed out");
    }

    @Test
    void runReasoner_clientLeavingDoesNotCancelTheRun() throws Exception {
        // Komet closing, or its call being cancelled, only stops it watching. The run is the
        // server's, and carries on to the end.
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<TrackingCallable<?>> tracker = new AtomicReference<>();
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any())).thenAnswer(invocation -> {
            tracker.set(invocation.getArgument(1));
            running.countDown();
            proceed.await(WAIT_MS, TimeUnit.MILLISECONDS);
            return null;
        });

        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<RunReasonerEvent> call = Mockito.mock(ServerCallStreamObserver.class);
        ArgumentCaptor<Runnable> onCancel = ArgumentCaptor.forClass(Runnable.class);

        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), call);
        assertThat(running.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        verify(call).setOnCancelHandler(onCancel.capture());

        onCancel.getValue().run();                 // the client goes away mid-run
        assertThat(tracker.get().isCancelled()).isFalse();

        proceed.countDown();
        // Nothing more reaches the departed caller — it was detached, not left to fail.
        Thread.sleep(200);
        verify(call, Mockito.never()).onCompleted();
        assertThat(tracker.get().isCancelled()).isFalse();
    }

    @Test
    void cancelReasoner_whileRunning_endsTheRunWithACancelledResult() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any())).thenAnswer(invocation -> {
            TrackingCallable<?> tracker = invocation.getArgument(1);
            running.countDown();
            while (!tracker.isCancelled()) {
                Thread.sleep(10);
            }
            throw new CancellationException("cancelled");
        });

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> watching = Mockito.mock(StreamObserver.class);
        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), watching);
        assertThat(running.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();

        @SuppressWarnings("unchecked")
        StreamObserver<CancelReasonerResponse> cancelling = Mockito.mock(StreamObserver.class);
        controller.cancelReasoner(CancelReasonerRequest.getDefaultInstance(), cancelling);

        verify(cancelling).onNext(any());
        verify(cancelling).onCompleted();
        verify(watching, timeout(WAIT_MS)).onCompleted();
        ArgumentCaptor<RunReasonerEvent> captor = ArgumentCaptor.forClass(RunReasonerEvent.class);
        verify(watching, Mockito.times(2)).onNext(captor.capture());
        RunReasonerResult result = captor.getValue().getResult();
        assertThat(result.getCancelled()).isTrue();
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEmpty();
    }

    @Test
    void cancelReasoner_whenNothingIsRunning_failsWithFailedPrecondition() {
        @SuppressWarnings("unchecked")
        StreamObserver<CancelReasonerResponse> cancelling = Mockito.mock(StreamObserver.class);

        controller.cancelReasoner(CancelReasonerRequest.getDefaultInstance(), cancelling);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(cancelling).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        verify(cancelling, Mockito.never()).onNext(any());
    }

    @Test
    void watchReasoner_beforeAnyRun_failsWithNotFound() {
        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> watching = Mockito.mock(StreamObserver.class);

        controller.watchReasoner(WatchReasonerRequest.getDefaultInstance(), watching);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(watching).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void watchReasoner_whileRunning_joinsTheRun() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any())).thenAnswer(invocation -> {
            ReasonerPhaseListener listener = invocation.getArgument(0);
            listener.onPhaseComplete(1, 4, "Loading data into reasoner");
            running.countDown();
            proceed.await(WAIT_MS, TimeUnit.MILLISECONDS);
            throw new IllegalStateException("stop here");
        });

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> starter = Mockito.mock(StreamObserver.class);
        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), starter);
        assertThat(running.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerEvent> rejoined = Mockito.mock(StreamObserver.class);
        controller.watchReasoner(WatchReasonerRequest.getDefaultInstance(), rejoined);
        proceed.countDown();

        verify(rejoined, timeout(WAIT_MS)).onCompleted();
        ArgumentCaptor<RunReasonerEvent> captor = ArgumentCaptor.forClass(RunReasonerEvent.class);
        verify(rejoined, Mockito.times(3)).onNext(captor.capture());
        List<RunReasonerEvent> events = captor.getAllValues();
        assertThat(events.get(0).getRun().getStarted()).isFalse();
        assertThat(events.get(1).getPhase().getStep()).isEqualTo(1);    // replayed
        assertThat(events.get(2).getResult().getErrorMessage()).isEqualTo("stop here");
    }
}
