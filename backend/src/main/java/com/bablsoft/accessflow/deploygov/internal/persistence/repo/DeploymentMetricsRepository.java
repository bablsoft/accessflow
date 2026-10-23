package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import java.util.List;

/**
 * Native read-time aggregates behind the deployment metrics (#984). Every percentile is in
 * seconds and {@code null} on an empty sample. Day buckets are UTC days regardless of the
 * session time zone.
 */
public interface DeploymentMetricsRepository {

    /** Executions in the window: frequency, change-failure counts and lead time. */
    ExecutionStats executionStats(DeploymentMetricsScope scope);

    /** Failures/rollbacks reported in the window and their restore by the next success. */
    RestoreStats restoreStats(DeploymentMetricsScope scope);

    /** Human-decided requests whose last decision falls in the window. */
    ApprovalStats approvalStats(DeploymentMetricsScope scope);

    /** Requests created in the window: review entry, timeouts and break-glass. */
    ReviewStats reviewStats(DeploymentMetricsScope scope);

    /** Executions per UTC day in the window; days without executions are absent. */
    List<DailyStats> daily(DeploymentMetricsScope scope);

    record ExecutionStats(long executed, long failed, long rolledBack, long unreported,
                          long commitSourced, Double leadTimeP50, Double leadTimeP95) {
    }

    record RestoreStats(long restored, long openIncidents, Double p50, Double p95) {
    }

    record ApprovalStats(long sampleSize, Double p50, Double p95) {
    }

    record ReviewStats(long timedOut, long enteredReview, long breakGlass) {
    }

    record DailyStats(String date, long deployments, long failed, long rolledBack) {
    }
}
