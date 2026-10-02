package dev.ikm.tinkar.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.service.service.AdminJobQueue;
import dev.ikm.tinkar.service.service.ChangesetJobService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How a changeset import or export ended — the final {@code result} event of its stream, and the
 * body of the blocking import.
 *
 * <p>One shape for both, so a client handles one result type: an import fills the counts, an
 * export the counts and where to download its file.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Outcome of a changeset import or export job")
public record ChangesetJobResult(
        @Schema(description = "The job, for GET /api/ike/admin/jobs/{jobId}/stream") String jobId,
        @Schema(description = "True if the job finished without error") boolean success,
        @Schema(description = "True if the job was cancelled (exports only; imports cannot be)") Boolean cancelled,
        @Schema(description = "Why the job failed") String errorMessage,
        @Schema(description = "Concepts imported or exported") Long conceptsCount,
        @Schema(description = "Semantics imported or exported") Long semanticsCount,
        @Schema(description = "Patterns imported or exported") Long patternsCount,
        @Schema(description = "Stamps imported or exported") Long stampsCount,
        @Schema(description = "All entities imported or exported") Long totalCount,
        @Schema(description = "Time the job spent running, in milliseconds") Long durationMs,
        @Schema(description = "Export: the file's name") String fileName,
        @Schema(description = "Export: the file's size in bytes") Long fileSizeBytes,
        @Schema(description = "Export: where to download the file; kept for an hour") String downloadUrl) {

    /** Maps an import's outcome. */
    public static ChangesetJobResult ofImport(String jobId, AdminJobQueue.Outcome<EntityCountSummary> outcome) {
        return of(jobId, outcome, outcome.result(), null, null, null);
    }

    /** Maps an export's outcome, pointing a successful one at its download. */
    public static ChangesetJobResult ofExport(String jobId, AdminJobQueue.Outcome<ChangesetJobService.ExportResult> outcome,
                                              String downloadUrl) {
        ChangesetJobService.ExportResult result = outcome.result();
        return result == null
                ? of(jobId, outcome, null, null, null, null)
                : of(jobId, outcome, result.counts(), result.fileName(), result.file().length(), downloadUrl);
    }

    private static ChangesetJobResult of(String jobId, AdminJobQueue.Outcome<?> outcome, EntityCountSummary counts,
                                         String fileName, Long fileSize, String downloadUrl) {
        boolean success = outcome.state() == AdminJobQueue.State.SUCCEEDED;
        return new ChangesetJobResult(jobId, success,
                outcome.state() == AdminJobQueue.State.CANCELLED ? Boolean.TRUE : null,
                outcome.errorMessage(),
                counts == null ? null : counts.conceptCount(),
                counts == null ? null : counts.semanticCount(),
                counts == null ? null : counts.patternCount(),
                counts == null ? null : counts.stampCount(),
                counts == null ? null : counts.getTotalCount(),
                outcome.durationMs(), fileName, fileSize, success ? downloadUrl : null);
    }
}
