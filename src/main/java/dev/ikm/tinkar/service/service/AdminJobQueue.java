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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs the service's long administrative jobs — the reasoner, changeset import and export — one
 * at a time, in the order they were asked for.
 *
 * <p>One at a time because they all touch the whole store: an import landing mid-classification,
 * or an export taken while the reasoner writes its results, would capture a half-finished state,
 * and two imports would interleave. A job asked for while another runs is queued, and is told what
 * it is waiting behind.
 *
 * <p>A job belongs to the server, not to the request that started it. Callers only watch: one that
 * goes away is detached and the job carries on, and anyone can attach later — while it is queued,
 * while it runs, or for a while after it ends — and is replayed what happened so far. Finished jobs
 * are kept for {@code admin.jobs.retention-ms} (an hour by default), then forgotten.
 */
@Component
public class AdminJobQueue {

    private static final Logger log = LoggerFactory.getLogger(AdminJobQueue.class);

    public enum Kind { REASONER, IMPORT, EXPORT }

    /** Where a job is. {@link #QUEUED} and {@link #RUNNING} are the only states that change. */
    public enum State {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

        public boolean finished() {
            return this != QUEUED && this != RUNNING;
        }
    }

    /** One step of progress, as the job reports it: {@code done} of {@code total}, and what it is doing. */
    public record Progress(long done, long total, String message) {
    }

    /** A job as listed to callers: identity, what it is, and where it is. */
    public record Summary(String id, Kind kind, String label, State state, long queuedAt, long startedAt) {
    }

    /**
     * How a job ended.
     *
     * @param result       what the job produced, for {@link State#SUCCEEDED} only
     * @param errorMessage why it failed, for {@link State#FAILED} only
     * @param durationMs   time spent running; 0 for a job cancelled while still queued
     */
    public record Outcome<R>(State state, R result, String errorMessage, long durationMs, long finishedAt) {
    }

    /**
     * Receives one job's events, in order: {@link #onAttached}, then — replayed for a late
     * attacher — the queue position, the start and each progress step so far, then the live ones,
     * then {@link #onFinished}.
     *
     * <p>Calls are serialized per job, so an implementation that writes to a stream needs no
     * locking of its own. Throwing from any method means the caller has gone: the watcher is
     * detached and nothing else happens — the job is not cancelled.
     */
    public interface Watcher<R> {
        /**
         * Called first.
         *
         * @param submitted true if this watcher's request created the job, false if it joined one
         */
        default void onAttached(Summary job, boolean submitted) throws Exception {
        }

        /** While queued: the jobs ahead, running one first. Sent again each time the queue moves. */
        default void onQueued(List<Summary> ahead) throws Exception {
        }

        default void onStarted(long startedAt) throws Exception {
        }

        default void onProgress(Progress progress) throws Exception {
        }

        void onFinished(Outcome<R> outcome) throws Exception;
    }

    /** The work a job does. Reports through {@link Job#progress} and stops early if {@link Job#isCancelled()}. */
    @FunctionalInterface
    public interface Body<R> {
        R run(Job<R> job) throws Exception;
    }

    /** One job, from queued to outcome. */
    public final class Job<R> {
        private final String id = UUID.randomUUID().toString();
        private final Kind kind;
        private final String label;
        private final boolean cancellable;
        private final Body<R> body;
        private final long queuedAt = System.currentTimeMillis();
        private final TrackingCallable<?> tracker = TinkarService.newCancellationTracker();
        private final List<Progress> progress = new ArrayList<>();
        private final List<Watcher<R>> watchers = new ArrayList<>();
        private final List<Consumer<Outcome<R>>> completionHooks = new ArrayList<>();
        private State state = State.QUEUED;
        private long startedAt;
        private List<Summary> lastAhead = List.of();
        private Outcome<R> outcome;

        private Job(Kind kind, String label, boolean cancellable, Body<R> body) {
            this.kind = kind;
            this.label = label;
            this.cancellable = cancellable;
            this.body = body;
        }

        public String id() {
            return id;
        }

        public Kind kind() {
            return kind;
        }

        public boolean cancellable() {
            return cancellable;
        }

        /** The handle the work checks and passes on, e.g. to the reasoner pipeline. */
        public TrackingCallable<?> tracker() {
            return tracker;
        }

        public boolean isCancelled() {
            return tracker.isCancelled();
        }

        public synchronized Summary summary() {
            return new Summary(id, kind, label, state, queuedAt, startedAt);
        }

        public synchronized boolean finished() {
            return state.finished();
        }

        /** How the job ended, or empty while it is queued or running. */
        public synchronized Optional<Outcome<R>> outcome() {
            return Optional.ofNullable(outcome);
        }

        /** Reports a step of progress to everyone watching, and keeps it for late attachers. */
        public synchronized void progress(long done, long total, String message) {
            Progress step = new Progress(done, total, message);
            progress.add(step);
            notifyAll(watcher -> watcher.onProgress(step));
        }

        /** Runs {@code hook} once the job has finished — at once if it already has. */
        public void whenFinished(Consumer<Outcome<R>> hook) {
            Outcome<R> ended;
            synchronized (this) {
                if (outcome == null) {
                    completionHooks.add(hook);
                    return;
                }
                ended = outcome;
            }
            hook.accept(ended);
        }

        synchronized void attach(Watcher<R> watcher, boolean submitted) {
            if (!deliver(watcher, () -> watcher.onAttached(summary(), submitted))) {
                return;
            }
            if (state == State.QUEUED && !deliver(watcher, () -> watcher.onQueued(lastAhead))) {
                return;
            }
            if (startedAt != 0 && !deliver(watcher, () -> watcher.onStarted(startedAt))) {
                return;
            }
            for (Progress step : progress) {
                if (!deliver(watcher, () -> watcher.onProgress(step))) {
                    return;
                }
            }
            if (outcome != null) {
                deliver(watcher, () -> watcher.onFinished(outcome));
                return;
            }
            watchers.add(watcher);
        }

        synchronized void detach(Watcher<R> watcher) {
            watchers.remove(watcher);
        }

        synchronized void queuedBehind(List<Summary> ahead) {
            lastAhead = ahead;
            notifyAll(watcher -> watcher.onQueued(ahead));
        }

        /** Ends a job that has not started yet. @return false if it had already started or ended */
        synchronized boolean cancelIfQueued() {
            if (state != State.QUEUED) {
                return false;
            }
            finish(State.CANCELLED, null, null);
            return true;
        }

        /** @return false if the job was cancelled while queued, and so must not run */
        synchronized boolean start() {
            if (state != State.QUEUED) {
                return false;
            }
            state = State.RUNNING;
            startedAt = System.currentTimeMillis();
            long at = startedAt;
            notifyAll(watcher -> watcher.onStarted(at));
            return true;
        }

        void finish(State endState, R result, String errorMessage) {
            List<Consumer<Outcome<R>>> hooks;
            synchronized (this) {
                if (state.finished()) {
                    return;
                }
                long now = System.currentTimeMillis();
                state = endState;
                outcome = new Outcome<>(endState, result, errorMessage,
                        startedAt == 0 ? 0 : now - startedAt, now);
                Outcome<R> ended = outcome;
                // A copy, and cleared first: finishing a watcher's stream can call back into
                // detach on this thread, which would otherwise edit the list mid-iteration.
                List<Watcher<R>> notify = List.copyOf(watchers);
                watchers.clear();
                for (Watcher<R> watcher : notify) {
                    deliver(watcher, () -> watcher.onFinished(ended));
                }
                hooks = List.copyOf(completionHooks);
                completionHooks.clear();
            }
            Outcome<R> ended = outcome;
            for (Consumer<Outcome<R>> hook : hooks) {
                try {
                    hook.accept(ended);
                } catch (RuntimeException e) {
                    log.warn("Completion hook for job {} failed: {}", id, e.toString());
                }
            }
        }

        private void notifyAll(Delivery<R> delivery) {
            for (Watcher<R> watcher : List.copyOf(watchers)) {
                if (!deliver(watcher, () -> delivery.deliver(watcher))) {
                    watchers.remove(watcher);
                }
            }
        }

        private boolean deliver(Watcher<R> watcher, Action action) {
            try {
                action.run();
                return true;
            } catch (Exception | Error e) {
                log.info("Watcher of {} job {} detached — its caller has gone ({})", kind, id, e.toString());
                return false;
            }
        }
    }

    @FunctionalInterface
    private interface Delivery<R> {
        void deliver(Watcher<R> watcher) throws Exception;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** Runs jobs. Single-threaded: that is what makes them one at a time, in order. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "admin-job");
        thread.setDaemon(true);
        return thread;
    });

    private final long retentionMs;

    /** Every job not yet forgotten, oldest first. */
    private final Map<String, Job<?>> jobs = new LinkedHashMap<>();

    public AdminJobQueue(@Value("${admin.jobs.retention-ms:3600000}") long retentionMs) {
        this.retentionMs = retentionMs;
    }

    /**
     * Queues a job behind those already waiting.
     *
     * @param cancellable whether {@link #cancel} may stop it; an import, for one, may not
     * @param first       attached before the job can start, so it cannot miss an event; may be null
     */
    public synchronized <R> Job<R> submit(Kind kind, String label, boolean cancellable, Body<R> body,
                                          Watcher<R> first) {
        forgetExpired();
        Job<R> job = new Job<>(kind, label, cancellable, body);
        List<Summary> ahead = unfinished();
        jobs.put(job.id, job);
        job.lastAhead = ahead;
        if (first != null) {
            job.attach(first, true);
        }
        log.info("Queued {} job {} ({}){}", kind, job.id, label,
                ahead.isEmpty() ? "" : " behind " + ahead.size() + " job(s)");
        executor.execute(() -> execute(job));
        return job;
    }

    /** The job with {@code id}, if it has not been forgotten. */
    public synchronized Optional<Job<?>> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** Attaches {@code watcher} to the job with {@code id}, replaying what it missed. */
    @SuppressWarnings("unchecked")
    public <R> Optional<Job<R>> watch(String id, Watcher<R> watcher) {
        Optional<Job<?>> found = find(id);
        found.ifPresent(job -> ((Job<R>) job).attach(watcher, false));
        return found.map(job -> (Job<R>) job);
    }

    /** Attaches {@code watcher} to {@code job}, replaying what it missed. */
    public <R> void watch(Job<R> job, Watcher<R> watcher) {
        job.attach(watcher, false);
    }

    /** Stops {@code watcher} receiving events. The job itself is unaffected. */
    public <R> void detach(Job<R> job, Watcher<R> watcher) {
        job.detach(watcher);
    }

    /** Every job still remembered, oldest first. */
    public synchronized List<Summary> list() {
        forgetExpired();
        return jobs.values().stream().map(Job::summary).toList();
    }

    /**
     * Asks a job to stop. A queued job ends as cancelled without running; a running one is asked to
     * stop and reports how it ended through its outcome.
     *
     * @return false if the job has already finished
     * @throws UnsupportedOperationException if the job is one that may not be cancelled
     */
    public boolean cancel(Job<?> job) {
        if (!job.cancellable) {
            throw new UnsupportedOperationException(job.kind + " jobs cannot be cancelled");
        }
        if (job.finished()) {
            return false;
        }
        log.info("Cancelling {} job {}", job.kind, job.id);
        job.tracker.cancel();
        // Outside the job's lock: moved() takes this queue's lock, and submit() takes the queue's
        // lock and then each job's — holding them the other way round here would deadlock.
        if (job.cancelIfQueued()) {
            moved();
        }
        return true;
    }

    private <R> void execute(Job<R> job) {
        if (!job.start()) {
            return; // cancelled while it waited
        }
        moved();
        log.info("Started {} job {}", job.kind, job.id);
        try {
            R result = job.body.run(job);
            job.finish(State.SUCCEEDED, result, null);
            log.info("{} job {} succeeded", job.kind, job.id);
        } catch (CancellationException e) {
            job.finish(State.CANCELLED, null, null);
            log.info("{} job {} cancelled: {}", job.kind, job.id, e.getMessage());
        } catch (Exception | Error e) {
            if (job.isCancelled() && causedByCancellation(e)) {
                // Work that wraps the cancel — the exporter rethrows it inside its own exception —
                // was still stopped by it, and must not be reported as a failure.
                job.finish(State.CANCELLED, null, null);
                log.info("{} job {} cancelled: {}", job.kind, job.id, rootMessage(e));
                moved();
                return;
            }
            // Error too: an OutOfMemoryError would otherwise end the thread with the job never
            // finished, and everything queued behind it would wait for good.
            job.finish(State.FAILED, null, rootMessage(e));
            log.error("{} job {} failed: {}", job.kind, job.id, e.getMessage(), e);
        }
        moved();
    }

    private static boolean causedByCancellation(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The innermost cause's message. Loaders wrap a subtask's failure a couple of times over, and
     * "Tinkar message value not set" says more to a caller than the wrapper class names around it.
     */
    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    /** Tells every queued job what it is now waiting behind. */
    private void moved() {
        List<Job<?>> queued;
        List<Summary> unfinished;
        synchronized (this) {
            queued = jobs.values().stream().filter(job -> job.summary().state() == State.QUEUED).toList();
            unfinished = unfinished();
        }
        for (Job<?> job : queued) {
            List<Summary> ahead = unfinished.stream()
                    .takeWhile(summary -> !summary.id().equals(job.id))
                    .toList();
            job.queuedBehind(ahead);
        }
    }

    /** Running job first, then queued ones in order. */
    private List<Summary> unfinished() {
        return jobs.values().stream()
                .map(Job::summary)
                .filter(summary -> !summary.state().finished())
                .sorted(Comparator.comparing((Summary summary) -> summary.state() != State.RUNNING)
                        .thenComparingLong(Summary::queuedAt))
                .toList();
    }

    private void forgetExpired() {
        long cutoff = System.currentTimeMillis() - retentionMs;
        jobs.values().removeIf(job -> job.outcome().map(o -> o.finishedAt() < cutoff).orElse(false));
    }

    @PreDestroy
    void shutdown() {
        List<Job<?>> unfinished;
        synchronized (this) {
            unfinished = jobs.values().stream().filter(job -> !job.finished()).toList();
        }
        unfinished.stream().filter(job -> job.cancellable).forEach(this::cancel);
        executor.shutdownNow();
    }
}
