package com.bablsoft.accessflow.core.api;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Result of a non-committing dry-run (issue AF-445): the engine's execution {@link QueryPlanNode}
 * tree, a best-effort estimated row impact, and the raw plan text — all produced <em>without</em>
 * executing or mutating data. {@code supported} is {@code false} for engines with no plan concept
 * (Redis, Cassandra/ScyllaDB, DynamoDB, custom JDBC); in that case {@code unsupportedReason} carries
 * a human-readable explanation (localized by the host service) and the plan fields are {@code null}.
 * {@code estimatedRows}/{@code plan}/{@code rawPlan} are individually nullable — engines fill in what
 * their EXPLAIN exposes.
 */
public record QueryDryRunResult(
        boolean supported,
        String engineId,
        QueryType queryType,
        Long estimatedRows,
        QueryPlanNode plan,
        String rawPlan,
        Set<UUID> appliedRowSecurityPolicyIds,
        Duration duration,
        String unsupportedReason) {

    public QueryDryRunResult {
        appliedRowSecurityPolicyIds = appliedRowSecurityPolicyIds == null
                ? Set.of() : Set.copyOf(appliedRowSecurityPolicyIds);
    }

    /** Degraded result for an engine that cannot produce a plan; the host fills in the reason. */
    public static QueryDryRunResult unsupported(String engineId) {
        return unsupported(engineId, null);
    }

    /** Degraded result carrying an engine-supplied reason (e.g. "INSERT has no plan"). */
    public static QueryDryRunResult unsupported(String engineId, String reason) {
        return new QueryDryRunResult(false, engineId, null, null, null, null, Set.of(),
                Duration.ZERO, reason);
    }

    public static QueryDryRunResult of(String engineId, QueryType queryType, Long estimatedRows,
                                       QueryPlanNode plan, String rawPlan,
                                       Set<UUID> appliedRowSecurityPolicyIds, Duration duration) {
        return new QueryDryRunResult(true, engineId, queryType, estimatedRows, plan, rawPlan,
                appliedRowSecurityPolicyIds, duration, null);
    }

    /** Returns a copy with the unsupported reason set (host localizes the message). */
    public QueryDryRunResult withUnsupportedReason(String reason) {
        return new QueryDryRunResult(supported, engineId, queryType, estimatedRows, plan, rawPlan,
                appliedRowSecurityPolicyIds, duration, reason);
    }
}
