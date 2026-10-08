package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.service.proto.EntityCountSummaryProto;
import dev.ikm.tinkar.service.proto.JobEvent;
import dev.ikm.tinkar.service.proto.JobInfo;
import dev.ikm.tinkar.service.proto.JobKind;
import dev.ikm.tinkar.service.proto.JobProgress;
import dev.ikm.tinkar.service.proto.JobQueuePosition;
import dev.ikm.tinkar.service.proto.JobResult;
import dev.ikm.tinkar.service.proto.JobState;
import dev.ikm.tinkar.service.service.AdminJobQueue;
import dev.ikm.tinkar.service.service.ChangesetJobService;

import java.util.List;

/** Maps {@link AdminJobQueue} jobs onto the {@code JobEvent} wire messages. */
final class JobEvents {

    private JobEvents() {
    }

    static JobEvent job(AdminJobQueue.Summary job) {
        return JobEvent.newBuilder().setJob(info(job)).build();
    }

    static JobEvent queued(List<AdminJobQueue.Summary> ahead) {
        JobQueuePosition.Builder position = JobQueuePosition.newBuilder();
        ahead.forEach(job -> position.addAhead(info(job)));
        return JobEvent.newBuilder().setQueued(position).build();
    }

    static JobEvent started(long startedAt) {
        return JobEvent.newBuilder().setStartedAt(startedAt).build();
    }

    static JobEvent progress(AdminJobQueue.Progress progress) {
        return JobEvent.newBuilder().setProgress(JobProgress.newBuilder()
                .setDone(progress.done())
                .setTotal(progress.total())
                .setMessage(progress.message() == null ? "" : progress.message())).build();
    }

    static JobEvent importResult(String jobId, AdminJobQueue.Outcome<EntityCountSummary> outcome) {
        return result(jobId, outcome, outcome.result(), null);
    }

    static JobEvent exportResult(String jobId, AdminJobQueue.Outcome<ChangesetJobService.ExportResult> outcome) {
        ChangesetJobService.ExportResult export = outcome.result();
        return result(jobId, outcome, export == null ? null : export.counts(), export);
    }

    private static JobEvent result(String jobId, AdminJobQueue.Outcome<?> outcome, EntityCountSummary counts,
                                   ChangesetJobService.ExportResult export) {
        JobResult.Builder result = JobResult.newBuilder()
                .setJobId(jobId)
                .setSuccess(outcome.state() == AdminJobQueue.State.SUCCEEDED)
                .setCancelled(outcome.state() == AdminJobQueue.State.CANCELLED)
                .setErrorMessage(outcome.errorMessage() == null ? "" : outcome.errorMessage())
                .setDurationMs(outcome.durationMs());
        if (counts != null) {
            result.setEntityCounts(EntityCountSummaryProto.newBuilder()
                    .setConceptsCount(counts.conceptCount())
                    .setSemanticsCount(counts.semanticCount())
                    .setPatternsCount(counts.patternCount())
                    .setStampsCount(counts.stampCount())
                    .setTotalCount(counts.getTotalCount()));
        }
        if (export != null) {
            result.setFileName(export.fileName()).setFileSizeBytes(export.file().length());
        }
        return JobEvent.newBuilder().setResult(result).build();
    }

    static JobInfo info(AdminJobQueue.Summary job) {
        return JobInfo.newBuilder()
                .setJobId(job.id())
                .setKind(switch (job.kind()) {
                    case REASONER -> JobKind.REASONER;
                    case IMPORT -> JobKind.IMPORT;
                    case EXPORT -> JobKind.EXPORT;
                })
                .setLabel(job.label())
                .setState(switch (job.state()) {
                    case QUEUED -> JobState.QUEUED;
                    case RUNNING -> JobState.RUNNING;
                    case SUCCEEDED -> JobState.SUCCEEDED;
                    case FAILED -> JobState.FAILED;
                    case CANCELLED -> JobState.CANCELLED;
                })
                .setQueuedAt(job.queuedAt())
                .setStartedAt(job.startedAt())
                .build();
    }
}
