package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * DORA metrics plus the approval-SLA half for deployments (#984, epic #983), computed at read
 * time over {@code deployment_requests} and {@code deployment_review_decisions} — no metrics
 * table, no snapshot job.
 *
 * <p>Time window {@code [from, to)} on the metric's own time axis; buckets are UTC days via
 * {@code date_trunc('day', ts AT TIME ZONE 'UTC')}.
 * <table>
 *   <caption>Metric definitions (epic #983, decision 2)</caption>
 *   <tr><th>Metric</th><th>Definition</th></tr>
 *   <tr><td>Deployment frequency</td><td>count of rows with {@code executed_at} in the window (an
 *   {@code EXECUTED → FAILED} flip keeps its {@code executed_at}, so it still counts as a
 *   deployment)</td></tr>
 *   <tr><td>Lead time (p50/p95)</td><td>{@code metadata->>'commit_timestamp'} (ISO-8601,
 *   documented key), falling back to {@code created_at}, → {@code executed_at}</td></tr>
 *   <tr><td>Change failure rate</td><td>rows with {@code outcome IN (FAILED, ROLLED_BACK)} ÷ rows
 *   with {@code executed_at} in the window; an unreported outcome counts as not-failed, and the
 *   {@code unreported} count is returned so the UI can caveat</td></tr>
 *   <tr><td>Time to restore (p50/p95)</td><td>for each {@code FAILED}/{@code ROLLED_BACK}
 *   outcome: {@code outcome_reported_at} → the {@code executed_at} of the next request on the
 *   same pipeline + environment whose outcome is {@code SUCCEEDED}; unrestored incidents are
 *   excluded from the percentiles and returned as {@code open_incidents}</td></tr>
 *   <tr><td>Approval latency (p50/p95)</td><td>{@code review_started_at} → the request's last
 *   {@code deployment_review_decisions.decided_at}; only requests decided by a human — routing
 *   auto-approvals and break-glass never waited on anyone</td></tr>
 *   <tr><td>Timeout rate</td><td>{@code TIMED_OUT} ÷ requests that entered review
 *   ({@code review_started_at IS NOT NULL}, or for rows pre-dating the column: a decision row
 *   exists or status is {@code TIMED_OUT})</td></tr>
 *   <tr><td>Break-glass count</td><td>rows with {@code submission_reason = 'EMERGENCY_ACCESS'}
 *   in the window, by {@code created_at}</td></tr>
 * </table>
 */
public interface DeploymentMetricsService {

    /**
     * Metrics for one pipeline. Visible to {@code DEPLOYMENT_PIPELINE_MANAGE},
     * {@code DEPLOYMENT_REVIEW}, {@code QUERY_ADMIN} and effective {@code can_trigger} holders.
     * An unknown environment name yields empty metrics, not an error.
     *
     * @throws DeploymentPipelineNotFoundException     unknown, cross-org, or not visible pipeline
     * @throws InvalidDeploymentMetricsPeriodException {@code from >= to} or a window over 366 days
     */
    DeploymentMetricsView pipelineMetrics(UUID pipelineId, UUID organizationId, UUID callerId,
                                          Set<Permission> callerPermissions,
                                          DeploymentMetricsFilter filter);

    /**
     * Metrics across the organization's pipelines, optionally narrowed by
     * {@link DeploymentMetricsFilter#pipelineId()}. The caller's functional permission is
     * enforced at the web layer; this read is plain org-scoped.
     *
     * @throws InvalidDeploymentMetricsPeriodException {@code from >= to} or a window over 366 days
     */
    DeploymentMetricsView organizationMetrics(UUID organizationId, DeploymentMetricsFilter filter);
}
