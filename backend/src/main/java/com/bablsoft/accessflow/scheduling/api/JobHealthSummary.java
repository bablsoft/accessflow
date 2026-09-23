package com.bablsoft.accessflow.scheduling.api;

import java.time.Instant;

/**
 * Rollup of one job's recorded history. The {@code last*} fields describe the newest run; the
 * window counts and mean cover the configured summary window only.
 *
 * @param consecutiveFailures {@code FAILED} runs recorded since the job's last {@code SUCCESS}
 */
public record JobHealthSummary(
        JobExecutionStatus lastStatus,
        boolean lastAbandoned,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        Long lastDurationMs,
        String lastErrorMessage,
        long consecutiveFailures,
        long windowSuccessCount,
        long windowFailureCount,
        Long windowMeanDurationMs) {

    public static JobHealthSummary empty() {
        return new JobHealthSummary(null, false, null, null, null, null, 0, 0, 0, null);
    }
}
