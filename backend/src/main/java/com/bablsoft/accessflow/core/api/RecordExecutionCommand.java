package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module command for {@link QueryRequestStateService#recordExecutionOutcome}. Pass
 * {@code rowsAffected = null} on failure; pass {@code errorMessage = null} on success.
 *
 * <p>{@code canonicalSql} and {@code previousRunId} are populated by
 * {@code QueryLifecycleService} on a successful run to support the query-diff feature
 * (AF-361). Both are {@code null} on failure, on the very first run of a given
 * canonical SQL, or when the canonicalizer produced no key (blank / comment-only SQL).
 */
public record RecordExecutionCommand(
        UUID queryRequestId,
        QueryStatus outcome,
        Long rowsAffected,
        Integer durationMs,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        String canonicalSql,
        UUID previousRunId) {

    public RecordExecutionCommand {
        if (outcome != QueryStatus.EXECUTED && outcome != QueryStatus.FAILED) {
            throw new IllegalArgumentException(
                    "outcome must be EXECUTED or FAILED, got " + outcome);
        }
    }
}
