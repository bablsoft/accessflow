package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable bundle of all routing signals gathered for one query, so condition evaluation is a pure
 * function of its inputs. Built in {@code workflow.internal} (re-parsing the SQL for tables /
 * WHERE / LIMIT / transactional, resolving requester role and group membership, and stamping the
 * evaluation time in the server's local zone).
 *
 * <p>{@code riskLevel} is {@code null} and {@code riskScore} is negative when AI analysis was
 * skipped (datasource {@code ai_analysis_enabled = false}); risk-based conditions evaluate to
 * {@code false} in that case.
 */
public record ConditionContext(
        QueryType queryType,
        Set<String> referencedTables,
        RiskLevel riskLevel,
        int riskScore,
        UserRoleType requesterRole,
        Set<UUID> requesterGroupIds,
        LocalDateTime evaluatedAt,
        boolean hasWhereClause,
        boolean hasLimitClause,
        boolean transactional) {

    public ConditionContext {
        referencedTables = Set.copyOf(referencedTables == null ? Set.of() : referencedTables);
        requesterGroupIds = Set.copyOf(requesterGroupIds == null ? Set.of() : requesterGroupIds);
    }

    /** @return {@code true} when an AI risk level / score signal is present. */
    public boolean hasRiskSignal() {
        return riskLevel != null && riskScore >= 0;
    }
}
