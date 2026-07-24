package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable bundle of all routing signals gathered for one query, so condition evaluation is a pure
 * function of its inputs. Built in {@code workflow.internal} (re-parsing the SQL for tables /
 * WHERE / LIMIT / transactional, resolving the requester's role name and group membership, and stamping the
 * evaluation time in the server's local zone).
 *
 * <p>{@code riskLevel} is {@code null} and {@code riskScore} is negative when AI analysis was
 * skipped (datasource {@code ai_analysis_enabled = false}); risk-based conditions evaluate to
 * {@code false} in that case.
 *
 * <p>The client-context signals are captured at submission and persisted on the query (routing runs
 * asynchronously, after AI completion, where no HTTP request exists): {@code requesterIpAddress}
 * and {@code requesterUserAgent} are {@code null} when unavailable, {@code ciCdOrigin} defaults to
 * {@code false}, and {@code minutesSinceLastApproval} is {@code null} when the requester has no
 * prior approval on the datasource. The matching client-context conditions fail closed on those
 * absent signals.
 *
 * <p>{@code anomalyActive} is {@code true} when the requester has at least one OPEN behavioural
 * anomaly (UBA, AF-383) on this datasource at submission time. Because anomaly detection is a
 * periodic batch over past audit data, it cannot mutate an already-executed query — instead it
 * raises this signal so a routing policy can ESCALATE the requester's <em>next</em> query.
 *
 * <p>{@code estimatedRows} and {@code scanType} carry the pre-flight cost estimate (AF-624), read
 * live at routing time from the persisted {@code query_estimates} row: {@code estimatedRows} is
 * the exact affected-row count for UPDATE/DELETE when available, otherwise the EXPLAIN estimate;
 * {@code scanType} is the plan's root operation (e.g. {@code Seq Scan}). Both are {@code null}
 * when the estimate is absent, unsupported, or failed — the matching conditions fail closed.
 */
public record ConditionContext(
        QueryType queryType,
        Set<String> referencedTables,
        RiskLevel riskLevel,
        int riskScore,
        String requesterRoleName,
        Set<UUID> requesterGroupIds,
        LocalDateTime evaluatedAt,
        boolean hasWhereClause,
        boolean hasLimitClause,
        boolean transactional,
        String requesterIpAddress,
        String requesterUserAgent,
        boolean ciCdOrigin,
        Integer minutesSinceLastApproval,
        boolean anomalyActive,
        Long estimatedRows,
        String scanType) {

    public ConditionContext {
        referencedTables = Set.copyOf(referencedTables == null ? Set.of() : referencedTables);
        requesterGroupIds = Set.copyOf(requesterGroupIds == null ? Set.of() : requesterGroupIds);
    }

    /** Backward-compatible constructor without the AF-624 estimate signals (defaults to absent). */
    public ConditionContext(QueryType queryType, Set<String> referencedTables, RiskLevel riskLevel,
                            int riskScore, String requesterRoleName, Set<UUID> requesterGroupIds,
                            LocalDateTime evaluatedAt, boolean hasWhereClause,
                            boolean hasLimitClause, boolean transactional,
                            String requesterIpAddress, String requesterUserAgent,
                            boolean ciCdOrigin, Integer minutesSinceLastApproval,
                            boolean anomalyActive) {
        this(queryType, referencedTables, riskLevel, riskScore, requesterRoleName,
                requesterGroupIds, evaluatedAt, hasWhereClause, hasLimitClause, transactional,
                requesterIpAddress, requesterUserAgent, ciCdOrigin, minutesSinceLastApproval,
                anomalyActive, null, null);
    }

    /** @return {@code true} when an AI risk level / score signal is present. */
    public boolean hasRiskSignal() {
        return riskLevel != null && riskScore >= 0;
    }

    /** @return {@code true} when a pre-flight estimated-row signal is present (AF-624). */
    public boolean hasEstimateSignal() {
        return estimatedRows != null;
    }
}
