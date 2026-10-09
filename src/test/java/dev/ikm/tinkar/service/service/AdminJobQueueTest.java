package dev.ikm.tinkar.service.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminJobQueueTest {

    private static final long WAIT_SECONDS = 5;

    private final AdminJobQueue queue = new AdminJobQueue(3_600_000L);

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void jobsRunOneAtATimeInTheOrderAskedFor() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        List<String> order = new CopyOnWriteArrayList<>();

        AdminJobQueue.Body<String> body = job -> {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            order.add(job.summary().label());
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            running.decrementAndGet();
            return job.summary().label();
        };
        Recorder<String> last = new Recorder<>();
        queue.submit(AdminJobQueue.Kind.REASONER, "first", true, body, null);
        queue.submit(AdminJobQueue.Kind.IMPORT, "second", false, body, null);
        queue.submit(AdminJobQueue.Kind.EXPORT, "third", true, body, last);
        release.countDown();

        assertThat(last.awaitOutcome().state()).isEqualTo(AdminJobQueue.State.SUCCEEDED);
        assertThat(order).containsExactly("first", "second", "third");
        assertThat(mostAtOnce).hasValue(1);
    }

    @Test
    void aQueuedJobIsToldWhatItWaitsBehind_andWhenThatMoves() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        queue.submit(AdminJobQueue.Kind.REASONER, "reasoner", true, job -> {
            firstStarted.countDown();
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        }, null);
        assertThat(firstStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Recorder<String> export = new Recorder<>();
        queue.submit(AdminJobQueue.Kind.EXPORT, "export", true, job -> "done", export);
        release.countDown();
        export.awaitOutcome();

        assertThat(export.events.getFirst()).isEqualTo("attached submitted=true state=QUEUED");
        assertThat(export.events.get(1)).isEqualTo("queued behind [reasoner:RUNNING]");
        assertThat(export.events).contains("queued behind []", "started");
    }

    @Test
    void cancellingAQueuedJob_endsItWithoutRunningIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        queue.submit(AdminJobQueue.Kind.REASONER, "blocker", true, job -> {
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        }, null);
        AtomicInteger ran = new AtomicInteger();
        Recorder<String> export = new Recorder<>();
        AdminJobQueue.Job<String> job = queue.submit(AdminJobQueue.Kind.EXPORT, "export", true, j -> {
            ran.incrementAndGet();
            return "done";
        }, export);

        assertThat(queue.cancel(job)).isTrue();
        assertThat(export.awaitOutcome().state()).isEqualTo(AdminJobQueue.State.CANCELLED);
        assertThat(export.awaitOutcome().durationMs()).isZero();
        release.countDown();

        Recorder<Object> after = new Recorder<>();
        queue.submit(AdminJobQueue.Kind.EXPORT, "after", true, j -> null, after);
        after.awaitOutcome();
        assertThat(ran).hasValue(0);
    }

    @Test
    void anImportCannotBeCancelled() {
        CountDownLatch release = new CountDownLatch(1);
        AdminJobQueue.Job<Object> job = queue.submit(AdminJobQueue.Kind.IMPORT, "import", false, j -> {
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        }, null);
        try {
            assertThatThrownBy(() -> queue.cancel(job)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(job.isCancelled()).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void aRunningJobThatHonoursItsTracker_endsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Recorder<Object> watcher = new Recorder<>();
        AdminJobQueue.Job<Object> job = queue.submit(AdminJobQueue.Kind.REASONER, "reasoner", true, j -> {
            started.countDown();
            while (!j.isCancelled()) {
                Thread.sleep(10);
            }
            throw new CancellationException("stopped");
        }, watcher);
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        queue.cancel(job);

        assertThat(watcher.awaitOutcome().state()).isEqualTo(AdminJobQueue.State.CANCELLED);
    }

    @Test
    void aCancelThatTheWorkWraps_isStillReportedAsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Recorder<Object> watcher = new Recorder<>();
        AdminJobQueue.Job<Object> job = queue.submit(AdminJobQueue.Kind.EXPORT, "export", true, j -> {
            started.countDown();
            while (!j.isCancelled()) {
                Thread.sleep(10);
            }
            throw new RuntimeException(new CancellationException("Aggregation cancelled"));
        }, watcher);
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        queue.cancel(job);

        assertThat(watcher.awaitOutcome().state()).isEqualTo(AdminJobQueue.State.CANCELLED);
    }

    @Test
    void aFailedJob_reportsWhy_andDoesNotHoldUpTheQueue() throws Exception {
        Recorder<Object> failed = new Recorder<>();
        queue.submit(AdminJobQueue.Kind.IMPORT, "bad", false, j -> {
            throw new IllegalStateException("Tinkar message value not set");
        }, failed);
        Recorder<String> next = new Recorder<>();
        queue.submit(AdminJobQueue.Kind.EXPORT, "next", true, j -> "ok", next);

        AdminJobQueue.Outcome<Object> outcome = failed.awaitOutcome();
        assertThat(outcome.state()).isEqualTo(AdminJobQueue.State.FAILED);
        assertThat(outcome.errorMessage()).isEqualTo("Tinkar message value not set");
        assertThat(next.awaitOutcome().result()).isEqualTo("ok");
    }

    @Test
    void watchingAFinishedJob_replaysItsProgressAndOutcome() throws Exception {
        Recorder<String> first = new Recorder<>();
        AdminJobQueue.Job<String> job = queue.submit(AdminJobQueue.Kind.EXPORT, "export", true, j -> {
            j.progress(1, 2, "half");
            j.progress(2, 2, "all");
            return "file";
        }, first);
        first.awaitOutcome();

        Recorder<String> later = new Recorder<>();
        assertThat(queue.watch(job.id(), later)).isPresent();

        assertThat(later.awaitOutcome().result()).isEqualTo("file");
        assertThat(later.events).containsExactly(
                "attached submitted=false state=SUCCEEDED", "started", "progress 1/2 half", "progress 2/2 all");
    }

    @Test
    void finishedJobsAreForgottenAfterTheRetentionPeriod() throws Exception {
        AdminJobQueue shortMemory = new AdminJobQueue(0L);
        try {
            Recorder<String> watcher = new Recorder<>();
            AdminJobQueue.Job<String> job = shortMemory.submit(AdminJobQueue.Kind.EXPORT, "export", true, j -> "x", watcher);
            watcher.awaitOutcome();
            Thread.sleep(5);

            assertThat(shortMemory.list()).isEmpty();
            assertThat(shortMemory.find(job.id())).isEmpty();
        } finally {
            shortMemory.shutdown();
        }
    }

    private static final class Recorder<R> implements AdminJobQueue.Watcher<R> {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<AdminJobQueue.Outcome<R>> outcome = new CompletableFuture<>();

        @Override
        public void onAttached(AdminJobQueue.Summary job, boolean submitted) {
            events.add("attached submitted=" + submitted + " state=" + job.state());
        }

        @Override
        public void onQueued(List<AdminJobQueue.Summary> ahead) {
            events.add("queued behind " + ahead.stream().map(s -> s.label() + ":" + s.state()).toList());
        }

        @Override
        public void onStarted(long startedAt) {
            events.add("started");
        }

        @Override
        public void onProgress(AdminJobQueue.Progress progress) {
            events.add("progress " + progress.done() + "/" + progress.total() + " " + progress.message());
        }

        @Override
        public void onFinished(AdminJobQueue.Outcome<R> finished) {
            outcome.complete(finished);
        }

        AdminJobQueue.Outcome<R> awaitOutcome() throws Exception {
            return outcome.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
