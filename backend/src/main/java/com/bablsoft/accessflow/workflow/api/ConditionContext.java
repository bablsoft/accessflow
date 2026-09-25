package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryShape;
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
 *
 * <p>{@code estimatedBytesScanned} (#941) is the warehouse engines' pre-flight scan estimate in raw
 * bytes from the same row — {@code null} for every engine that reports none, and whenever the
 * estimate is absent or failed.
 *
 * <p>{@code dataBudgetUsedPercent} (#942) is the share of the submitter's most-used data budget on
 * this datasource, as a whole percentage — {@code null} when no budget applies, the statement is not
 * a SELECT, and on the historical replay path (usage then is not reconstructable).
 *
 * <p>{@code queryShapes} are the structural features re-derived from the SQL with the WHERE / LIMIT
 * signals (#940). {@code shapesAnalyzed} is {@code false} when the SQL could not be parsed or walked
 * (typically a non-SQL engine); the {@code query_shape} condition then fails closed.
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
        String scanType,
        Set<QueryShape> queryShapes,
        boolean shapesAnalyzed,
        Long estimatedBytesScanned,
        Integer dataBudgetUsedPercent) {

    public ConditionContext {
        referencedTables = Set.copyOf(referencedTables == null ? Set.of() : referencedTables);
        requesterGroupIds = Set.copyOf(requesterGroupIds == null ? Set.of() : requesterGroupIds);
        queryShapes = Set.copyOf(queryShapes == null ? Set.of() : queryShapes);
    }

    /** Backward-compatible constructor without the #942 data-budget signal (defaults to absent). */
    public ConditionContext(QueryType queryType, Set<String> referencedTables, RiskLevel riskLevel,
                            int riskScore, String requesterRoleName, Set<UUID> requesterGroupIds,
                            LocalDateTime evaluatedAt, boolean hasWhereClause,
                            boolean hasLimitClause, boolean transactional,
                            String requesterIpAddress, String requesterUserAgent,
                            boolean ciCdOrigin, Integer minutesSinceLastApproval,
                            boolean anomalyActive, Long estimatedRows, String scanType,
                            Set<QueryShape> queryShapes, boolean shapesAnalyzed,
                            Long estimatedBytesScanned) {
        this(queryType, referencedTables, riskLevel, riskScore, requesterRoleName,
                requesterGroupIds, evaluatedAt, hasWhereClause, hasLimitClause, transactional,
                requesterIpAddress, requesterUserAgent, ciCdOrigin, minutesSinceLastApproval,
                anomalyActive, estimatedRows, scanType, queryShapes, shapesAnalyzed,
                estimatedBytesScanned, null);
    }

    /** Backward-compatible constructor without the #941 bytes estimate (defaults to absent). */
    public ConditionContext(QueryType queryType, Set<String> referencedTables, RiskLevel riskLevel,
                            int riskScore, String requesterRoleName, Set<UUID> requesterGroupIds,
                            LocalDateTime evaluatedAt, boolean hasWhereClause,
                            boolean hasLimitClause, boolean transactional,
                            String requesterIpAddress, String requesterUserAgent,
                            boolean ciCdOrigin, Integer minutesSinceLastApproval,
                            boolean anomalyActive, Long estimatedRows, String scanType,
                            Set<QueryShape> queryShapes, boolean shapesAnalyzed) {
        this(queryType, referencedTables, riskLevel, riskScore, requesterRoleName,
                requesterGroupIds, evaluatedAt, hasWhereClause, hasLimitClause, transactional,
                requesterIpAddress, requesterUserAgent, ciCdOrigin, minutesSinceLastApproval,
                anomalyActive, estimatedRows, scanType, queryShapes, shapesAnalyzed, null);
    }

    /** Backward-compatible constructor without the #940 shape signals (defaults to not analyzed). */
    public ConditionContext(QueryType queryType, Set<String> referencedTables, RiskLevel riskLevel,
                            int riskScore, String requesterRoleName, Set<UUID> requesterGroupIds,
                            LocalDateTime evaluatedAt, boolean hasWhereClause,
                            boolean hasLimitClause, boolean transactional,
                            String requesterIpAddress, String requesterUserAgent,
                            boolean ciCdOrigin, Integer minutesSinceLastApproval,
                            boolean anomalyActive, Long estimatedRows, String scanType) {
        this(queryType, referencedTables, riskLevel, riskScore, requesterRoleName,
                requesterGroupIds, evaluatedAt, hasWhereClause, hasLimitClause, transactional,
                requesterIpAddress, requesterUserAgent, ciCdOrigin, minutesSinceLastApproval,
                anomalyActive, estimatedRows, scanType, Set.of(), false);
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
                anomalyActive, null, null, Set.of(), false, null, null);
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
