package dev.ikm.tinkar.service.service;

import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasonerRunManagerTest {

    private static final long WAIT_SECONDS = 5;

    private TinkarService tinkarService;
    private ReasonerRunManager manager;

    /** Released to let the fake pipeline move past its first phase. */
    private CountDownLatch proceed;
    /** Counted down once the fake pipeline has reported its first phase. */
    private CountDownLatch firstPhaseReported;
    /** The tracker the manager handed the most recent run. */
    private final AtomicReference<TrackingCallable<?>> tracker = new AtomicReference<>();
    /** What the fake pipeline does after its first phase, if not to succeed. */
    private final AtomicReference<RuntimeException> failWith = new AtomicReference<>();
    private final ClassifierResults results = mock(ClassifierResults.class);

    @BeforeEach
    void setUp() throws Exception {
        tinkarService = mock(TinkarService.class);
        manager = new ReasonerRunManager(tinkarService, new AdminJobQueue(3_600_000L));
        proceed = new CountDownLatch(1);
        firstPhaseReported = new CountDownLatch(1);

        // A pipeline that reports step 1, waits to be let go, then — unless cancelled or told to
        // fail — reports step 2 and succeeds. Mirrors how the real one reacts to its tracker.
        when(tinkarService.runReasoner(any(), any())).thenAnswer(invocation -> {
            ReasonerPhaseListener listener = invocation.getArgument(0);
            TrackingCallable<?> runTracker = invocation.getArgument(1);
            tracker.set(runTracker);
            listener.onPhaseComplete(1, 2, "first");
            firstPhaseReported.countDown();
            while (!proceed.await(20, TimeUnit.MILLISECONDS)) {
                if (runTracker.isCancelled()) {
                    throw new CancellationException("cancelled");
                }
            }
            if (failWith.get() != null) {
                throw failWith.get();
            }
            listener.onPhaseComplete(2, 2, "second");
            return results;
        });
    }

    @AfterEach
    void tearDown() {
        proceed.countDown();
        manager.shutdown();
    }

    @Test
    void runOrAttach_startsARunAndReportsItToTheEnd() throws Exception {
        RecordingWatcher watcher = new RecordingWatcher();

        ReasonerRunManager.Attachment attachment = manager.runOrAttach(watcher);
        proceed.countDown();
        ReasonerRunManager.Outcome outcome = watcher.awaitOutcome();

        assertThat(attachment.started()).isTrue();
        assertThat(watcher.events).containsExactly("attached started=true", "phase 1", "phase 2");
        assertThat(outcome.state()).isEqualTo(ReasonerRunManager.State.SUCCEEDED);
        assertThat(outcome.results()).isSameAs(results);
    }

    @Test
    void runOrAttach_whileRunning_joinsTheRunAndReplaysWhatItMissed() throws Exception {
        RecordingWatcher first = new RecordingWatcher();
        manager.runOrAttach(first);
        awaitFirstPhase();

        RecordingWatcher second = new RecordingWatcher();
        ReasonerRunManager.Attachment attachment = manager.runOrAttach(second);
        proceed.countDown();

        assertThat(attachment.started()).isFalse();
        assertThat(second.awaitOutcome().state()).isEqualTo(ReasonerRunManager.State.SUCCEEDED);
        assertThat(second.events).containsExactly("attached started=false", "phase 1", "phase 2");
        assertThat(first.awaitOutcome().state()).isEqualTo(ReasonerRunManager.State.SUCCEEDED);
        verify(tinkarService, times(1)).runReasoner(any(), any());
    }

    @Test
    void aWatcherThatGoesAway_isDetached_andTheRunCarriesOn() throws Exception {
        AtomicInteger phasesSeen = new AtomicInteger();
        ReasonerRunManager.Watcher gone = new ReasonerRunManager.Watcher() {
            @Override
            public void onPhase(ReasonerRunManager.Phase phase) throws Exception {
                phasesSeen.incrementAndGet();
                throw new java.io.IOException("Broken pipe");
            }

            @Override
            public void onFinished(ReasonerRunManager.Outcome outcome) {
                throw new AssertionError("A detached watcher should not hear the outcome");
            }
        };
        manager.runOrAttach(gone);
        awaitFirstPhase();
        proceed.countDown();

        RecordingWatcher later = new RecordingWatcher();
        awaitIdle();
        manager.watch(later);

        assertThat(tracker.get().isCancelled()).isFalse();
        assertThat(later.awaitOutcome().state()).isEqualTo(ReasonerRunManager.State.SUCCEEDED);
        assertThat(phasesSeen).hasValue(1);
    }

    @Test
    void detach_stopsEventsWithoutStoppingTheRun() throws Exception {
        RecordingWatcher watcher = new RecordingWatcher();
        ReasonerRunManager.Run run = manager.runOrAttach(watcher).run();
        awaitFirstPhase();

        manager.detach(run, watcher);
        proceed.countDown();
        awaitIdle();

        assertThat(tracker.get().isCancelled()).isFalse();
        assertThat(run.outcome()).map(ReasonerRunManager.Outcome::state)
                .contains(ReasonerRunManager.State.SUCCEEDED);
        assertThat(watcher.events).containsExactly("attached started=true", "phase 1");
        assertThat(watcher.outcome).isNotDone();
    }

    @Test
    void cancel_whenIdle_isRefused() {
        assertThat(manager.cancel()).isFalse();
    }

    @Test
    void cancel_whileRunning_stopsTheRunAndTellsEveryWatcher() throws Exception {
        RecordingWatcher watcher = new RecordingWatcher();
        manager.runOrAttach(watcher);
        awaitFirstPhase();

        assertThat(manager.cancel()).isTrue();

        assertThat(watcher.awaitOutcome().state()).isEqualTo(ReasonerRunManager.State.CANCELLED);
        assertThat(tracker.get().isCancelled()).isTrue();
        assertThat(manager.cancel()).isFalse();
    }

    @Test
    void watch_beforeAnyRun_findsNothing() throws Exception {
        assertThat(manager.watch(new RecordingWatcher())).isEmpty();
        verify(tinkarService, times(0)).runReasoner(any());
    }

    @Test
    void watch_afterTheRunEnded_replaysItsPhasesAndOutcome() throws Exception {
        manager.runOrAttach(new RecordingWatcher());
        proceed.countDown();
        awaitIdle();

        RecordingWatcher later = new RecordingWatcher();
        assertThat(manager.watch(later)).isPresent();

        assertThat(later.awaitOutcome().state()).isEqualTo(ReasonerRunManager.State.SUCCEEDED);
        assertThat(later.events).containsExactly("attached started=false", "phase 1", "phase 2");
        verify(tinkarService, times(1)).runReasoner(any(), any());
    }

    @Test
    void aFailedRun_reportsWhy_andTheNextRequestStartsAFreshRun() throws Exception {
        failWith.set(new IllegalStateException("ELK ran out of memory"));
        RecordingWatcher watcher = new RecordingWatcher();
        manager.runOrAttach(watcher);
        proceed.countDown();

        ReasonerRunManager.Outcome outcome = watcher.awaitOutcome();
        assertThat(outcome.state()).isEqualTo(ReasonerRunManager.State.FAILED);
        assertThat(outcome.errorMessage()).isEqualTo("ELK ran out of memory");

        failWith.set(null);
        assertThat(manager.runOrAttach(new RecordingWatcher()).started()).isTrue();
    }

    private void awaitFirstPhase() throws InterruptedException {
        assertThat(firstPhaseReported.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    /** Waits for the current run to end, whether or not anything is watching it. */
    private void awaitIdle() throws Exception {
        RecordingWatcher probe = new RecordingWatcher();
        manager.watch(probe);
        probe.awaitOutcome();
    }

    private static final class RecordingWatcher implements ReasonerRunManager.Watcher {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<ReasonerRunManager.Outcome> outcome = new CompletableFuture<>();

        @Override
        public void onAttached(long startedAt, boolean started) {
            events.add("attached started=" + started);
        }

        @Override
        public void onPhase(ReasonerRunManager.Phase phase) {
            events.add("phase " + phase.step());
        }

        @Override
        public void onFinished(ReasonerRunManager.Outcome finished) {
            outcome.complete(finished);
        }

        ReasonerRunManager.Outcome awaitOutcome() throws Exception {
            return outcome.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
