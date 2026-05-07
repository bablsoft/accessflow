package com.partqam.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module command for {@link QueryRequestStateService#recordExecutionOutcome}. Pass
 * {@code rowsAffected = null} on failure; pass {@code errorMessage = null} on success.
 */
public record RecordExecutionCommand(
        UUID queryRequestId,
        QueryStatus outcome,
        Long rowsAffected,
        Integer durationMs,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {

    public RecordExecutionCommand {
        if (outcome != QueryStatus.EXECUTED && outcome != QueryStatus.FAILED) {
            throw new IllegalArgumentException(
                    "outcome must be EXECUTED or FAILED, got " + outcome);
        }
    }
}
