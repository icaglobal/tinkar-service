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

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the reasoner run, so that a run belongs to the server rather than to the call that
 * started it.
 *
 * <p>A classification can take long enough that the person who started it closes the page or
 * quits Komet before it ends. Tying the run to the call would throw that work away, so callers
 * only <em>watch</em> a run: a caller that goes away is detached and the run carries on. Stopping
 * a run is a separate, explicit {@link #cancel()}.
 *
 * <p>At most one reasoner run exists at a time, shared by every protocol, and a request to run
 * while one is queued or running attaches to it instead of starting a second. Runs go through the
 * {@link AdminJobQueue} with imports and exports, so a classification never overlaps either —
 * one asked for while an import runs waits its turn, and is told what it waits behind.
 *
 * <p>The most recent run is kept after it ends, so a caller that reconnects later still sees how
 * it finished. It is held in memory only, until the next run starts or the server restarts.
 */
@Component
public class ReasonerRunManager {

    private static final Logger log = LoggerFactory.getLogger(ReasonerRunManager.class);

    /** Where a run is. Every state but {@link #RUNNING} is final; a queued run counts as running. */
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
     * locking of its own. Throwing from any method means the caller has gone: the watcher is
     * detached and nothing else happens — in particular the run is not cancelled.
     */
    public interface Watcher {
        /**
         * Called first, before any replayed phase.
         *
         * @param startedAt when the run was asked for, in epoch milliseconds
         * @param started   true if this watcher's request started the run, false if it joined
         *                  one already queued, running or finished
         */
        default void onAttached(long startedAt, boolean started) throws Exception {
        }

        /** While the run waits for another job — an import, say — to finish: the jobs ahead of it. */
        default void onQueued(List<AdminJobQueue.Summary> ahead) throws Exception {
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

    /** One classification, from request to outcome. */
    public static final class Run {
        private final AdminJobQueue.Job<ClassifierResults> job;

        private Run(AdminJobQueue.Job<ClassifierResults> job) {
            this.job = job;
        }

        /** When the run was asked for. */
        public long startedAt() {
            return job.summary().queuedAt();
        }

        /** True while the run is queued or running. */
        public boolean isRunning() {
            return !job.finished();
        }

        /** How the run ended, or empty while it is still queued or running. */
        public Optional<Outcome> outcome() {
            return job.outcome().map(ReasonerRunManager::toOutcome);
        }
    }

    private final TinkarService tinkarService;

    private final AdminJobQueue queue;

    /** Each watcher's adapter onto the queue, so {@link #detach} can find the one to remove. */
    private final Map<Watcher, AdminJobQueue.Watcher<ClassifierResults>> adapters = new IdentityHashMap<>();

    /** The queued or running run, or the last one to finish; null only before the first run. */
    private Run current;

    public ReasonerRunManager(TinkarService tinkarService, AdminJobQueue queue) {
        this.tinkarService = tinkarService;
        this.queue = queue;
    }

    /**
     * Attaches {@code watcher} to the queued or running classification, asking for one first if
     * there is none.
     */
    public synchronized Attachment runOrAttach(Watcher watcher) {
        if (current != null && current.isRunning()) {
            log.info("Reasoner already asked for at {} — attaching to it", current.startedAt());
            queue.watch(current.job, adapterFor(watcher));
            return new Attachment(current, false);
        }
        AdminJobQueue.Job<ClassifierResults> job = queue.submit(
                AdminJobQueue.Kind.REASONER, "Reasoner classification", true,
                running -> tinkarService.runReasoner(
                        (step, totalSteps, message) -> running.progress(step, totalSteps, message),
                        running.tracker()),
                adapterFor(watcher));
        current = new Run(job);
        return new Attachment(current, true);
    }

    /**
     * Attaches {@code watcher} to the queued or running classification, or replays the last one if
     * it has finished. Never starts a run.
     *
     * @return the run watched, or empty if no run has happened since the server started
     */
    public synchronized Optional<Run> watch(Watcher watcher) {
        if (current == null) {
            return Optional.empty();
        }
        queue.watch(current.job, adapterFor(watcher));
        return Optional.of(current);
    }

    /** Stops {@code watcher} receiving events. The run itself is unaffected. */
    public void detach(Run run, Watcher watcher) {
        AdminJobQueue.Watcher<ClassifierResults> adapter;
        synchronized (adapters) {
            adapter = adapters.remove(watcher);
        }
        if (adapter != null) {
            queue.detach(run.job, adapter);
        }
    }

    /**
     * Asks the queued or running classification to stop.
     *
     * <p>Returns at once; watchers learn that the run stopped from its outcome. A queued run ends
     * without starting. A run already writing its inferred results finishes the write and ends
     * {@link State#SUCCEEDED} — see
     * {@link TinkarService#runReasoner(ReasonerPhaseListener, TrackingCallable)}.
     *
     * @return false if nothing is queued or running
     */
    public synchronized boolean cancel() {
        if (current == null || !current.isRunning()) {
            return false;
        }
        log.info("Cancelling the reasoner run asked for at {}", current.startedAt());
        return queue.cancel(current.job);
    }

    private AdminJobQueue.Watcher<ClassifierResults> adapterFor(Watcher watcher) {
        AdminJobQueue.Watcher<ClassifierResults> adapter = new AdminJobQueue.Watcher<>() {
            @Override
            public void onAttached(AdminJobQueue.Summary job, boolean submitted) throws Exception {
                watcher.onAttached(job.queuedAt(), submitted);
            }

            @Override
            public void onQueued(List<AdminJobQueue.Summary> ahead) throws Exception {
                watcher.onQueued(ahead);
            }

            @Override
            public void onProgress(AdminJobQueue.Progress progress) throws Exception {
                watcher.onPhase(new Phase((int) progress.done(), (int) progress.total(), progress.message()));
            }

            @Override
            public void onFinished(AdminJobQueue.Outcome<ClassifierResults> outcome) throws Exception {
                synchronized (adapters) {
                    adapters.remove(watcher);
                }
                watcher.onFinished(toOutcome(outcome));
            }
        };
        synchronized (adapters) {
            adapters.put(watcher, adapter);
        }
        return adapter;
    }

    /** Maps a reasoner job's outcome, as {@link AdminJobQueue} reports it, onto this class's. */
    public static Outcome toOutcome(AdminJobQueue.Outcome<ClassifierResults> outcome) {
        State state = switch (outcome.state()) {
            case SUCCEEDED -> State.SUCCEEDED;
            case FAILED -> State.FAILED;
            case CANCELLED -> State.CANCELLED;
            case QUEUED, RUNNING -> State.RUNNING;
        };
        return new Outcome(state, outcome.result(), outcome.errorMessage(),
                outcome.durationMs(), outcome.finishedAt());
    }

    @PreDestroy
    void shutdown() {
        cancel();
    }
}
