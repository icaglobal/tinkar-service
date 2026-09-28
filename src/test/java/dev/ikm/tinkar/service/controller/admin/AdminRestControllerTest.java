package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.service.service.ReasonerPhaseListener;
import dev.ikm.tinkar.service.service.ReasonerRunManager;
import dev.ikm.tinkar.service.service.TinkarService;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.factory.primitive.IntLists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRestControllerTest {

    @Mock
    private TinkarService tinkarService;

    // Constructed rather than @InjectMocks: the controller also takes the stream timeout, a long
    // Mockito cannot supply. The run manager is real — it is what the reasoner endpoints are
    // about — over the mocked service.
    private AdminRestController controller;

    private ReasonerRunManager reasonerRuns;

    @BeforeEach
    void createController() {
        reasonerRuns = new ReasonerRunManager(tinkarService);
        controller = new AdminRestController(tinkarService, reasonerRuns, 14_400_000L);
    }

    // -------------------------------------------------------------------------
    // importChangeset
    // -------------------------------------------------------------------------

    @Test
    void importChangeset_delegatesToServiceAndReturns200() throws Exception {
        // Arrange
        byte[] content = "fake-zip-bytes".getBytes();
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "test.zip", "application/zip", content);

        EntityCountSummaryResponse mockResult = Mockito.mock(EntityCountSummaryResponse.class);
        when(tinkarService.importChangeset(any(File.class), anyBoolean())).thenReturn(mockResult);

        // Act
        ResponseEntity<EntityCountSummaryResponse> response =
                controller.importChangeset(multipartFile, true);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(mockResult);
        verify(tinkarService).importChangeset(any(File.class), anyBoolean());
    }

    @Test
    void importChangeset_withMultiPassFalse_passesFalseToService() throws Exception {
        // Arrange
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "test.zip", "application/zip", new byte[]{1, 2, 3});

        EntityCountSummaryResponse mockResult = Mockito.mock(EntityCountSummaryResponse.class);
        when(tinkarService.importChangeset(any(File.class), anyBoolean())).thenReturn(mockResult);

        // Act
        ResponseEntity<EntityCountSummaryResponse> response =
                controller.importChangeset(multipartFile, false);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(tinkarService).importChangeset(any(File.class), Mockito.eq(false));
    }

    @Test
    void importChangeset_whenServiceThrows_returnsErrorResponse() throws Exception {
        // Arrange
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "bad.zip", "application/zip", new byte[]{9});

        when(tinkarService.importChangeset(any(File.class), anyBoolean()))
                .thenThrow(new RuntimeException("disk full"));

        // Act
        ResponseEntity<EntityCountSummaryResponse> response =
                controller.importChangeset(multipartFile, true);

        // Assert — controller catches and wraps as EntityCountSummaryResponse.error(...)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().errorMessage()).contains("disk full");
    }

    // -------------------------------------------------------------------------
    // runReasoner
    // -------------------------------------------------------------------------

    @Test
    void runReasoner_waitsForTheRunAndReturnsItsCounts() throws Exception {
        ClassifierResults results = Mockito.mock(ClassifierResults.class);
        when(results.getClassificationConceptSet()).thenReturn(IntLists.immutable.of(1, 2, 3));
        when(results.getConceptsWithInferredChanges()).thenReturn(IntLists.immutable.of(1));
        when(results.getConceptsWithNavigationChanges()).thenReturn(IntLists.immutable.empty());
        when(results.getEquivalentSets()).thenReturn(Sets.immutable.empty());
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any())).thenReturn(results);

        ResponseEntity<ReasonerResultsResponse> response = controller.runReasoner();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().classifiedConceptCount()).isEqualTo(3);
        assertThat(response.getBody().inferredChangesCount()).isEqualTo(1);
    }

    @Test
    void runReasoner_reportsAFailedRunAsAnError() throws Exception {
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any()))
                .thenThrow(new IllegalStateException("Exceptions: 4"));

        ResponseEntity<ReasonerResultsResponse> response = controller.runReasoner();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().errorMessage()).isEqualTo("Exceptions: 4");
    }

    @Test
    void watchReasoner_beforeAnyRun_returns204() {
        assertThat(controller.watchReasoner().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void cancelReasoner_whenNothingIsRunning_returns409() {
        ResponseEntity<Map<String, String>> response = controller.cancelReasoner();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void cancelReasoner_whileRunning_returns202AndStopsTheRun() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        when(tinkarService.runReasoner(any(ReasonerPhaseListener.class), any())).thenAnswer(invocation -> {
            dev.ikm.tinkar.common.service.TrackingCallable<?> tracker = invocation.getArgument(1);
            running.countDown();
            while (!tracker.isCancelled()) {
                Thread.sleep(10);
            }
            throw new CancellationException("cancelled");
        });
        // Started over the stream endpoint, which — unlike the blocking one — returns at once.
        controller.runReasonerStreaming();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<Map<String, String>> response = controller.cancelReasoner();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        java.util.concurrent.CompletableFuture<ReasonerRunManager.Outcome> outcome =
                new java.util.concurrent.CompletableFuture<>();
        reasonerRuns.watch(new ReasonerRunManager.Watcher() {
            @Override
            public void onPhase(ReasonerRunManager.Phase phase) {
            }

            @Override
            public void onFinished(ReasonerRunManager.Outcome finished) {
                outcome.complete(finished);
            }
        });
        assertThat(outcome.get(5, TimeUnit.SECONDS).state()).isEqualTo(ReasonerRunManager.State.CANCELLED);
        assertThat(ReasonerResultsResponse.from(outcome.get()).cancelled()).isTrue();
    }
}
