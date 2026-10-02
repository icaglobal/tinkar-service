package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.service.service.ReasonerPhaseListener;
import dev.ikm.tinkar.service.service.AdminJobQueue;
import dev.ikm.tinkar.service.service.ChangesetJobService;
import dev.ikm.tinkar.service.dto.ChangesetJobResult;
import dev.ikm.tinkar.service.dto.ExportRequest;
import dev.ikm.tinkar.common.service.EntityCountSummary;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
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

    private AdminJobQueue jobs;

    @TempDir
    Path exportDir;

    @BeforeEach
    void createController() throws Exception {
        jobs = new AdminJobQueue(3_600_000L);
        reasonerRuns = new ReasonerRunManager(tinkarService, jobs);
        ChangesetJobService changesets = new ChangesetJobService(tinkarService, jobs, exportDir.toString(), 3_600_000L);
        controller = new AdminRestController(tinkarService, reasonerRuns, jobs, changesets, 14_400_000L);
    }

    // -------------------------------------------------------------------------
    // importChangeset
    // -------------------------------------------------------------------------

    @Test
    void importChangeset_reportsEachCountFromTheImport() throws Exception {
        // Each count separately: the service used to report the concept count in all four.
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "test.zip", "application/zip", "fake-zip-bytes".getBytes());
        when(tinkarService.importChangeset(any(File.class), anyBoolean(), any()))
                .thenReturn(new EntityCountSummary(1, 2, 3, 4));

        ResponseEntity<EntityCountSummaryResponse> response = controller.importChangeset(multipartFile, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().conceptsCount()).isEqualTo(1);
        assertThat(response.getBody().semanticsCount()).isEqualTo(2);
        assertThat(response.getBody().patternsCount()).isEqualTo(3);
        assertThat(response.getBody().stampsCount()).isEqualTo(4);
    }

    @Test
    void importChangeset_withMultiPassFalse_passesFalseToService() throws Exception {
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "test.zip", "application/zip", new byte[]{1, 2, 3});
        when(tinkarService.importChangeset(any(File.class), anyBoolean(), any()))
                .thenReturn(new EntityCountSummary(0, 0, 0, 0));

        controller.importChangeset(multipartFile, false);

        verify(tinkarService).importChangeset(any(File.class), Mockito.eq(false), any());
    }

    @Test
    void importChangeset_whenTheImportFails_returnsErrorResponse() throws Exception {
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "bad.zip", "application/zip", new byte[]{9});
        when(tinkarService.importChangeset(any(File.class), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("Tinkar message value not set"));

        ResponseEntity<EntityCountSummaryResponse> response = controller.importChangeset(multipartFile, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().errorMessage()).contains("Tinkar message value not set");
    }

    @Test
    void importChangeset_deletesTheUploadOnceTheImportEnds() throws Exception {
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "test.zip", "application/zip", new byte[]{1});
        java.util.concurrent.atomic.AtomicReference<File> uploaded = new java.util.concurrent.atomic.AtomicReference<>();
        when(tinkarService.importChangeset(any(File.class), anyBoolean(), any())).thenAnswer(invocation -> {
            uploaded.set(invocation.getArgument(0));
            return new EntityCountSummary(0, 0, 0, 0);
        });

        controller.importChangeset(multipartFile, true);

        assertThat(uploaded.get()).isNotNull();
        assertThat(uploaded.get()).doesNotExist();
    }

    // -------------------------------------------------------------------------
    // export
    // -------------------------------------------------------------------------

    @Test
    void exportEntities_returnsTheZipTheServiceWrote() throws Exception {
        when(tinkarService.exportEntities(any(File.class), any(ExportRequest.class), any(), any())).thenAnswer(invocation -> {
            java.nio.file.Files.writeString(((File) invocation.getArgument(0)).toPath(), "zip-bytes");
            return new EntityCountSummary(1, 1, 0, 1);
        });

        ResponseEntity<?> response = controller.exportEntities(
                new ExportRequest(ExportRequest.ExportType.FULL, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .startsWith("attachment; filename=\"tinkar-export-FULL-");
        assertThat(((org.springframework.core.io.Resource) response.getBody()).contentLength()).isEqualTo(9);
    }

    @Test
    void exportEntities_temporalWithoutARange_isRejectedBeforeItQueues() {
        ResponseEntity<?> response = controller.exportEntities(
                new ExportRequest(ExportRequest.ExportType.TEMPORAL, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jobs.list()).isEmpty();
    }

    @Test
    void exportEntities_whenTheExportFails_returns500AndLeavesNoFile() throws Exception {
        when(tinkarService.exportEntities(any(File.class), any(ExportRequest.class), any(), any())).thenAnswer(invocation -> {
            java.nio.file.Files.writeString(((File) invocation.getArgument(0)).toPath(), "partial");
            throw new IllegalStateException("store closed");
        });

        ResponseEntity<?> response = controller.exportEntities(
                new ExportRequest(ExportRequest.ExportType.FULL, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(((ChangesetJobResult) response.getBody()).errorMessage()).isEqualTo("store closed");
        try (var left = java.nio.file.Files.list(exportDir)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void cancelJob_stopsARunningExport_andLeavesNoFile() throws Exception {
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        when(tinkarService.exportEntities(any(File.class), any(ExportRequest.class), any(), any())).thenAnswer(invocation -> {
            java.nio.file.Files.writeString(((File) invocation.getArgument(0)).toPath(), "partial");
            java.util.function.BooleanSupplier cancelled = invocation.getArgument(3);
            running.countDown();
            while (!cancelled.getAsBoolean()) {
                Thread.sleep(10);
            }
            throw new java.util.concurrent.CancellationException("Aggregation cancelled");
        });
        controller.exportEntitiesStreaming(new ExportRequest(ExportRequest.ExportType.FULL, null, null, null));
        assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        String jobId = jobs.list().getFirst().id();

        assertThat(controller.cancelJob(jobId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        AdminJobQueue.Job<?> job = jobs.find(jobId).orElseThrow();
        for (int i = 0; i < 500 && !job.finished(); i++) {
            Thread.sleep(10);
        }
        assertThat(job.summary().state()).isEqualTo(AdminJobQueue.State.CANCELLED);
        try (var left = java.nio.file.Files.list(exportDir)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void cancelJob_refusesAnImport() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(tinkarService.importChangeset(any(File.class), anyBoolean(), any())).thenAnswer(invocation -> {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new EntityCountSummary(0, 0, 0, 0);
        });
        controller.importChangesetStreaming(new MockMultipartFile("file", "a.zip", "application/zip", new byte[]{1}), true);
        try {
            ResponseEntity<Map<String, String>> response = controller.cancelJob(jobs.list().getFirst().id());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().get("error")).contains("cannot be cancelled");
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancelJob_unknownJob_returns404() {
        assertThat(controller.cancelJob("no-such-job").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadExport_unknownJob_returns404() {
        assertThat(controller.downloadExport("no-such-job").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listJobs_includesFinishedImports() throws Exception {
        when(tinkarService.importChangeset(any(File.class), anyBoolean(), any()))
                .thenReturn(new EntityCountSummary(0, 0, 0, 0));
        controller.importChangeset(new MockMultipartFile("file", "named.zip", "application/zip", new byte[]{1}), true);

        assertThat(controller.listJobs())
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.kind()).isEqualTo(AdminJobQueue.Kind.IMPORT);
                    assertThat(job.label()).isEqualTo("Import named.zip");
                    assertThat(job.state()).isEqualTo(AdminJobQueue.State.SUCCEEDED);
                });
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
