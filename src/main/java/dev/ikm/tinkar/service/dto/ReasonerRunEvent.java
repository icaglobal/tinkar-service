package dev.ikm.tinkar.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The first event on a reasoner stream: which run the caller is now watching.
 *
 * <p>A run belongs to the server, not to the request that started it, so a caller may join one
 * already in progress — or one that has already ended. This tells it which, so it can say
 * "reconnected to a run started at …" rather than implying it just started one.
 */
@Schema(description = "Which reasoner run a stream is watching")
public record ReasonerRunEvent(
        @Schema(description = "When the run started, in epoch milliseconds") long startedAt,

        @Schema(description = "True if this request started the run; false if it attached to one "
                + "already running or already finished") boolean started) {
}
