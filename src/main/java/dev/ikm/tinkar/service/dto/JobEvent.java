package dev.ikm.tinkar.service.dto;

import dev.ikm.tinkar.service.service.AdminJobQueue;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Payloads of the events on a job stream, before its final {@code result}. */
public final class JobEvent {

    private JobEvent() {
    }

    /** First event: which job this stream follows. */
    @Schema(description = "The job a stream is following")
    public record Attached(String jobId, AdminJobQueue.Kind kind, String label, AdminJobQueue.State state,
                           long queuedAt, long startedAt,
                           @Schema(description = "True if this request created the job, false if it joined one")
                           boolean submitted) {
        public static Attached of(AdminJobQueue.Summary job, boolean submitted) {
            return new Attached(job.id(), job.kind(), job.label(), job.state(), job.queuedAt(), job.startedAt(), submitted);
        }
    }

    /** While the job waits: the jobs ahead of it, the running one first. */
    @Schema(description = "Jobs ahead of this one in the queue")
    public record Queued(List<AdminJobQueue.Summary> ahead) {
    }

    @Schema(description = "When the job started running")
    public record Started(long startedAt) {
    }
}
