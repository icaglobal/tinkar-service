package dev.ikm.tinkar.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ikm.tinkar.service.service.ReasonerRunManager;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Results from running the reasoner classification pipeline")
public record ReasonerResultsResponse(
        @Schema(description = "Number of concepts in the classification set") Integer classifiedConceptCount,
        @Schema(description = "Number of concepts with inferred changes") Integer inferredChangesCount,
        @Schema(description = "Number of concepts with navigation changes") Integer navigationChangesCount,
        @Schema(description = "Number of equivalent concept sets detected") Integer equivalentSetsCount,
        @Schema(description = "Number of concepts with cycles detected") Integer cyclesCount,
        @Schema(description = "Number of orphaned concepts detected") Integer orphansCount,
        @Schema(description = "Duration of the reasoner pipeline in milliseconds") Long durationMs,
        @Schema(description = "Whether the operation was successful") Boolean success,
        @Schema(description = "Error message if operation failed") String errorMessage,
        @Schema(description = "Unix epoch milliseconds when this response was generated") Long createdAt,
        @Schema(description = "True if the run was cancelled before it wrote anything; absent otherwise") Boolean cancelled) {

    public static ReasonerResultsResponse success(int classifiedConceptCount,
            int inferredChangesCount, int navigationChangesCount,
            int equivalentSetsCount, int cyclesCount, int orphansCount, long durationMs) {
        return new ReasonerResultsResponse(classifiedConceptCount, inferredChangesCount,
                navigationChangesCount, equivalentSetsCount, cyclesCount, orphansCount,
                durationMs, true, null, System.currentTimeMillis(), null);
    }

    /**
     * Maps a completed classification onto this response.
     *
     * <p>One definition so the blocking and streaming endpoints cannot report the same run
     * differently — they did briefly, when the streaming path was written separately and lost
     * the null guards below.
     *
     * <p>Cycles and orphans are null-guarded because {@code ClassifierResults} leaves them unset
     * when the reasoner found none, rather than returning an empty collection.
     */
    public static ReasonerResultsResponse from(
            dev.ikm.tinkar.reasoner.service.ClassifierResults results, long durationMs) {
        return success(
                results.getClassificationConceptSet().size(),
                results.getConceptsWithInferredChanges().size(),
                results.getConceptsWithNavigationChanges().size(),
                results.getEquivalentSets().size(),
                results.getCycles() != null ? results.getCycles().size() : 0,
                results.getOrphans() != null ? results.getOrphans().size() : 0,
                durationMs);
    }

    public static ReasonerResultsResponse error(String errorMessage) {
        return new ReasonerResultsResponse(null, null, null, null, null, null, null,
                false, errorMessage, System.currentTimeMillis(), null);
    }

    /**
     * A run stopped by a cancel. Not a success — nothing was classified or written — but not a
     * failure either, so it carries no error message.
     */
    public static ReasonerResultsResponse cancelled(long durationMs) {
        return new ReasonerResultsResponse(null, null, null, null, null, null, durationMs,
                false, null, System.currentTimeMillis(), true);
    }

    /** Maps how a run ended onto this response, whichever way it ended. */
    public static ReasonerResultsResponse from(ReasonerRunManager.Outcome outcome) {
        return switch (outcome.state()) {
            case SUCCEEDED -> from(outcome.results(), outcome.durationMs());
            case CANCELLED -> cancelled(outcome.durationMs());
            case FAILED -> error(outcome.errorMessage());
            case RUNNING -> throw new IllegalArgumentException("A running run has no outcome yet");
        };
    }
}
