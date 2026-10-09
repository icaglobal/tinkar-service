package dev.ikm.tinkar.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What to export: the whole store, the changes in a time range, or the members of tags.
 *
 * <p>The same three choices the gRPC {@code ExportEntitiesRequest} offers, and the TEMPORAL one is
 * what Komet's export dialog calls a "Change set".
 */
@Schema(description = "Selects which entities to export")
public record ExportRequest(
        @Schema(description = "FULL (everything), TEMPORAL (changed in a time range) or MEMBERSHIP (members of tags)",
                example = "TEMPORAL") ExportType type,

        @Schema(description = "TEMPORAL: start of the range, epoch milliseconds, inclusive") Long fromEpochMillis,

        @Schema(description = "TEMPORAL: end of the range, epoch milliseconds, inclusive") Long toEpochMillis,

        @Schema(description = "MEMBERSHIP: UUIDs of the tag patterns whose members to export") List<String> membershipTagIds) {

    public enum ExportType { FULL, TEMPORAL, MEMBERSHIP }

    /**
     * Rejects a request that cannot be run, so the caller hears why before a job is queued for it.
     *
     * @throws IllegalArgumentException naming what is missing or wrong
     */
    public void validate() {
        if (type == null) {
            throw new IllegalArgumentException("type is required: FULL, TEMPORAL or MEMBERSHIP");
        }
        switch (type) {
            case FULL -> { }
            case TEMPORAL -> {
                if (fromEpochMillis == null || toEpochMillis == null) {
                    throw new IllegalArgumentException("TEMPORAL export needs fromEpochMillis and toEpochMillis");
                }
                if (fromEpochMillis > toEpochMillis) {
                    throw new IllegalArgumentException("fromEpochMillis is after toEpochMillis");
                }
            }
            case MEMBERSHIP -> {
                if (membershipTagIds == null || membershipTagIds.isEmpty()) {
                    throw new IllegalArgumentException("MEMBERSHIP export needs at least one membershipTagIds entry");
                }
            }
        }
    }

    /** A short description for logs and job listings, e.g. "TEMPORAL 2026-09-01T00:00Z..2026-10-01T00:00Z". */
    public String describe() {
        return switch (type) {
            case FULL -> "FULL";
            case TEMPORAL -> "TEMPORAL " + java.time.Instant.ofEpochMilli(fromEpochMillis)
                    + ".." + java.time.Instant.ofEpochMilli(toEpochMillis);
            case MEMBERSHIP -> "MEMBERSHIP " + membershipTagIds.size() + " tag(s)";
        };
    }
}
