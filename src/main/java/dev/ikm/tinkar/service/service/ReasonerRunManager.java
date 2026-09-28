/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.tinkar.service.service;

import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the reasoner run, so that a run belongs to the server rather than to the call that
 * started it.
 *
 * <p>A classification can take long enough that the person who started it closes the page or
 * quits Komet before it ends. Tying the run to the call would throw that work away, so callers
 * only <em>watch</em> a run: a caller that goes away is detached and the run carries on. Stopping
 * a run is a separate, explicit {@link #cancel()}.
 *
 * <p>At most one run exists at a time, shared by every protocol. The pipeline is a single
 * stateful run over one {@code ReasonerService} instance, so two concurrent runs would corrupt
 * each other — whether they arrived over REST, gRPC, or one of each. A request to run while one
 * is already going attaches to it instead of starting a second.
 *
 * <p>The most recent run is kept after it ends, so a caller that reconnects later still sees how
 * it finished. It is held in memory only, until the next run starts or the server restarts.
 */
@Component
public class ReasonerRunManager {

    private static final Logger log = LoggerFactory.getLogger(ReasonerRunManager.class);

    /** Where a run is. Every state but {@link #RUNNING} is final. */
    public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /** One completed phase, kept so a caller attaching late can be shown the ones it missed. */
    public record Phase(int step, int totalSteps, String message) {
    }

    /**
     * How a run ended.
     *
     * @param results      the classification, for {@link State#SUCCEEDED} only
     * @param errorMessage why it failed, for {@link State#FAILED} only
     */
    public record Outcome(State state, ClassifierResults results, String errorMessage,
                          long durationMs, long finishedAt) {
    }

    /**
     * Receives one run's events, in order: the phases completed so far, each later phase as it
     * completes, then the outcome.
     *
     * <p>Calls are serialized per run, so an implementation that writes to a stream needs no
     * locking of its own. Throwing from either method means the caller has gone: the watcher is
     * detached and nothing else happens — in particular the run is not cancelled.
     */
    public interface Watcher {
        /**
         * Called first, before any replayed phase.
         *
         * @param startedAt when the run started, in epoch milliseconds
         * @param started   true if this watcher's request started the run, false if it joined
         *                  one already running or already finished
         */
        default void onAttached(long startedAt, boolean started) throws Exception {
        }

        void onPhase(Phase phase) throws Exception;

        void onFinished(Outcome outcome) throws Exception;
    }

    /**
     * What {@link #runOrAttach} did.
     *
     * @param run     the run the watcher is now attached to
     * @param started true if this call started it, false if it joined one already running
     */
    public record Attachment(Run run, boolean started) {
    }

    /** One classification, from start to outcome. */
    public static final class Run {
        private final long startedAt = System.currentTimeMillis();
        private final TrackingCallable<?> tracker = TinkarService.newCancellationTracker();
        private final List<Phase> phases = new ArrayList<>();
        private final List<Watcher> watchers = new ArrayList<>();
        private Outcome outcome;

        public long startedAt() {
            return startedAt;
        }

        public synchronized boolean isRunning() {
            return outcome == null;
        }

        /** How the run ended, or empty while it is still running. */
        public synchronized Optional<Outcome> outcome() {
            return Optional.ofNullable(outcome);
        }

        /**
         * Replays what has happened so far to {@code watcher}, then keeps it informed. A run that
         * has already ended replays its phases and outcome, and keeps no watcher.
         */
        synchronized void attach(Watcher watcher, boolean started) {
            if (!deliver(watcher, () -> watcher.onAttached(startedAt, started))) {
                return;
            }
            for (Phase phase : phases) {
                if (!deliver(watcher, () -> watcher.onPhase(phase))) {
                    return;
                }
            }
            if (outcome != null) {
                deliver(watcher, () -> watcher.onFinished(outcome));
                return;
            }
            watchers.add(watcher);
        }

        synchronized void detach(Watcher watcher) {
            watchers.remove(watcher);
        }

        // Both iterate a copy: a delivery can end its caller's stream, and a stream's completion
        // callback detaches it — re-entering this lock on the same thread to edit the list.
        synchronized void publish(Phase phase) {
            phases.add(phase);
            for (Watcher watcher : List.copyOf(watchers)) {
                if (!deliver(watcher, () -> watcher.onPhase(phase))) {
                    watchers.remove(watcher);
                }
            }
        }

        synchronized void finish(Outcome finished) {
            outcome = finished;
            List<Watcher> notify = List.copyOf(watchers);
            watchers.clear();
            for (Watcher watcher : notify) {
                deliver(watcher, () -> watcher.onFinished(finished));
            }
        }

        /** @return false if the watcher failed, meaning its caller has gone */
        private static boolean deliver(Watcher watcher, Delivery delivery) {
            try {
                delivery.run();
                return true;
            } catch (Exception | Error e) {
                log.info("Reasoner watcher detached — its caller has gone ({})", e.toString());
                return false;
            }
        }

        @FunctionalInterface
        private interface Delivery {
            void run() throws Exception;
        }
    }

    private final TinkarService tinkarService;

    /**
     * Runs classifications. Single-threaded because only one run exists at a time; the queue
     * never holds more than the one task being executed.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "reasoner-run");
        thread.setDaemon(true);
        return thread;
    });

    /** The running run, or the last one to finish; null only before the first run. */
    private Run current;

    public ReasonerRunManager(TinkarService tinkarService) {
        this.tinkarService = tinkarService;
    }

    /**
     * Attaches {@code watcher} to the running classification, starting one first if none is
     * running.
     */
    public synchronized Attachment runOrAttach(Watcher watcher) {
        if (current != null && current.isRunning()) {
            log.info("Reasoner already running since {} — attaching to it", current.startedAt());
            current.attach(watcher, false);
            return new Attachment(current, false);
        }
        Run run = new Run();
        // Attached before the run is submitted, so the watcher cannot miss its first phase.
        run.attach(watcher, true);
        current = run;
        executor.execute(() -> execute(run));
        return new Attachment(run, true);
    }

    /**
     * Attaches {@code watcher} to the running classification, or replays the last one if it has
     * finished. Never starts a run.
     *
     * @return the run watched, or empty if no run has happened since the server started
     */
    public synchronized Optional<Run> watch(Watcher watcher) {
        if (current == null) {
            return Optional.empty();
        }
        current.attach(watcher, false);
        return Optional.of(current);
    }

    /** Stops {@code watcher} receiving events. The run itself is unaffected. */
    public void detach(Run run, Watcher watcher) {
        run.detach(watcher);
    }

    /**
     * Asks the running classification to stop.
     *
     * <p>Returns at once; watchers learn that the run stopped from its outcome. A run already
     * writing its inferred results finishes the write and ends {@link State#SUCCEEDED} — see
     * {@link TinkarService#runReasoner(ReasonerPhaseListener, TrackingCallable)}.
     *
     * @return false if nothing is running
     */
    public synchronized boolean cancel() {
        if (current == null || !current.isRunning()) {
            return false;
        }
        log.info("Cancelling the reasoner run started at {}", current.startedAt());
        current.tracker.cancel();
        return true;
    }

    private void execute(Run run) {
        log.info("Reasoner run started");
        Outcome outcome;
        try {
            ClassifierResults results = tinkarService.runReasoner(
                    (step, totalSteps, message) -> run.publish(new Phase(step, totalSteps, message)),
                    run.tracker);
            outcome = ended(run, State.SUCCEEDED, results, null);
            log.info("Reasoner run finished in {}ms", outcome.durationMs());
        } catch (CancellationException e) {
            outcome = ended(run, State.CANCELLED, null, null);
            log.info("Reasoner run cancelled: {}", e.getMessage());
        } catch (Exception | Error e) {
            // Error too: an OutOfMemoryError from ELK on a large dataset would otherwise end the
            // thread with the run never finished, so it would count as running for good and
            // every later request would attach to a run that no longer exists.
            outcome = ended(run, State.FAILED, null,
                    e.getMessage() == null ? e.toString() : e.getMessage());
            log.error("Reasoner run failed: {}", e.getMessage(), e);
        }
        // Last: finishing is what lets the next run start, so it waits until this one is done.
        run.finish(outcome);
    }

    private static Outcome ended(Run run, State state, ClassifierResults results, String error) {
        long now = System.currentTimeMillis();
        return new Outcome(state, results, error, now - run.startedAt(), now);
    }

    @PreDestroy
    void shutdown() {
        cancel();
        executor.shutdownNow();
    }
}
