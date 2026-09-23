package com.bablsoft.accessflow.scheduling.api;

import java.time.Duration;

/**
 * One job as the scheduler registered it, merged with its recorded health.
 *
 * <p>Reports the configured cadence, never a predicted next run: every job uses a fixed delay, so
 * the next fire time depends on when the last run finished and is not knowable cluster-wide.
 *
 * @param registered {@code false} for a job that has recorded history but is not registered in this
 *                   process (renamed or removed since, or scheduling is disabled here)
 * @param cadence    ISO-8601 duration for fixed-delay/fixed-rate jobs, the cron text for cron jobs;
 *                   {@code null} when not registered
 */
public record JobDescriptor(
        String jobName,
        String declaringClass,
        String methodName,
        String module,
        JobCadenceType cadenceType,
        String cadence,
        String lockName,
        Duration lockAtMostFor,
        boolean registered,
        JobHealthSummary health) {
}
