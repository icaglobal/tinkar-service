package dev.ikm.tinkar.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One completed phase of the reasoner pipeline, as sent to a streaming caller.
 *
 * <p>Carries the same step, total and wording the gRPC {@code ReasonerPhase} message does, both
 * taken from {@code ReasonerPhaseListener.Phase} — so a progress bar driven over REST shows what
 * one driven over gRPC shows, and neither can drift from Komet's local wording.
 */
@Schema(description = "A completed phase of the reasoner pipeline")
public record ReasonerPhaseEvent(
        @Schema(description = "1-based phase that just completed", example = "2") int step,

        @Schema(description = "Total phases in the pipeline", example = "4") int totalSteps,

        @Schema(description = "What the phase did", example = "Computing inferences") String message) {
}
