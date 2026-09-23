package com.bablsoft.accessflow.scheduling.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One recorded run of a scheduled job.
 *
 * @param abandoned {@code true} when the row is still {@code RUNNING} but older than the job's
 *                  {@code lockAtMostFor} — the replica that ran it died before closing the row.
 */
public record JobExecutionView(
        UUID id,
        String jobName,
        String lockName,
        String instanceId,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        JobExecutionStatus status,
        String errorClass,
        String errorMessage,
        boolean abandoned) {
}
