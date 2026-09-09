package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One historical query as the policy simulator (issue AF-630) replays it: every signal the routing,
 * row-security and masking simulations need, gathered on the read side so the simulation loop
 * itself does no per-row lookup.
 *
 * <p>The corpus is {@code query_requests} rather than {@code query_snapshots}: routing runs at
 * submission, so restricting to executed queries would under-report an auto-reject's blast radius,
 * and only {@code query_requests} carries the AF-446 client context ({@code submittedIp},
 * {@code submittedUserAgent}, {@code ciCdOrigin}) that the client-context routing operands read.
 * It is also the only corpus reachable from {@code proxy} without closing a Spring Modulith cycle —
 * {@code query_snapshots} belongs to {@code workflow}, which already depends on {@code proxy}.
 *
 * <p>{@code aiRiskLevel} is {@code null} and {@code aiRiskScore} {@code null} when AI analysis was
 * skipped or failed; the risk-based routing operands fail closed on that, exactly as in production.
 */
public record QueryCorpusRow(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        String datasourceName,
        DbType dbType,
        UUID submittedByUserId,
        String submittedByEmail,
        String submittedByDisplayName,
        String sqlText,
        QueryType queryType,
        QueryStatus status,
        boolean transactional,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        boolean aiFailed,
        String submittedIp,
        String submittedUserAgent,
        boolean ciCdOrigin,
        Instant createdAt) {
}
