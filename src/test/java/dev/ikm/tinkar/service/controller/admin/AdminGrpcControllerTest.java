package dev.ikm.tinkar.service.controller.admin;

import com.google.protobuf.ByteString;
import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.service.proto.ImportChangesetRequest;
import dev.ikm.tinkar.service.proto.ImportChangesetResponse;
import dev.ikm.tinkar.service.proto.RunReasonerRequest;
import dev.ikm.tinkar.service.proto.RunReasonerResponse;
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
    void runReasoner_successPath_callsOnNextAndOnCompleted() {
        // Arrange
        ReasonerResultsResponse mockResult = Mockito.mock(ReasonerResultsResponse.class);
        when(mockResult.success()).thenReturn(true);
        when(mockResult.errorMessage()).thenReturn(null);
        when(mockResult.classifiedConceptCount()).thenReturn(100);
        when(mockResult.inferredChangesCount()).thenReturn(50);
        when(mockResult.navigationChangesCount()).thenReturn(25);
        when(mockResult.equivalentSetsCount()).thenReturn(5);
        when(mockResult.cyclesCount()).thenReturn(0);
        when(mockResult.orphansCount()).thenReturn(2);
        when(mockResult.durationMs()).thenReturn(3000L);

        when(tinkarService.runReasoner()).thenReturn(mockResult);

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerResponse> responseObserver = Mockito.mock(StreamObserver.class);

        // Act
        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), responseObserver);

        // Assert
        ArgumentCaptor<RunReasonerResponse> captor =
                ArgumentCaptor.forClass(RunReasonerResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        RunReasonerResponse sent = captor.getValue();
        assertThat(sent.getSuccess()).isTrue();
        assertThat(sent.getResults().getClassifiedConceptCount()).isEqualTo(100);
        assertThat(sent.getResults().getInferredChangesCount()).isEqualTo(50);
        assertThat(sent.getResults().getNavigationChangesCount()).isEqualTo(25);
        assertThat(sent.getResults().getEquivalentSetsCount()).isEqualTo(5);
        assertThat(sent.getResults().getCyclesCount()).isEqualTo(0);
        assertThat(sent.getResults().getOrphansCount()).isEqualTo(2);
        assertThat(sent.getDurationMs()).isEqualTo(3000L);
    }

    @Test
    void runReasoner_failurePath_setsSuccessFalseAndNoResults() {
        // Arrange
        ReasonerResultsResponse mockResult = Mockito.mock(ReasonerResultsResponse.class);
        when(mockResult.success()).thenReturn(false);
        when(mockResult.errorMessage()).thenReturn("reasoner timed out");

        when(tinkarService.runReasoner()).thenReturn(mockResult);

        @SuppressWarnings("unchecked")
        StreamObserver<RunReasonerResponse> responseObserver = Mockito.mock(StreamObserver.class);

        // Act
        controller.runReasoner(RunReasonerRequest.getDefaultInstance(), responseObserver);

        // Assert
        ArgumentCaptor<RunReasonerResponse> captor =
                ArgumentCaptor.forClass(RunReasonerResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        RunReasonerResponse sent = captor.getValue();
        assertThat(sent.getSuccess()).isFalse();
        assertThat(sent.getErrorMessage()).isEqualTo("reasoner timed out");
        assertThat(sent.hasResults()).isFalse();
    }
}
