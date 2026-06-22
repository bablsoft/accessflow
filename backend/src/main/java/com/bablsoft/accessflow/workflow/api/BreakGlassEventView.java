package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin "Break-glass log" row (AF-385): a single emergency execution and its retro-review state.
 * {@code executionStatus} is the executed query's terminal status (EXECUTED / FAILED), distinct
 * from {@code status} which is the retro-review lifecycle.
 */
public record BreakGlassEventView(
        UUID id,
        UUID queryRequestId,
        UUID organizationId,
        UUID datasourceId,
        String datasourceName,
        UUID submittedByUserId,
        String submittedByDisplayName,
        String submittedByEmail,
        String sqlText,
        QueryStatus executionStatus,
        String justification,
        BreakGlassStatus status,
        UUID reviewedByUserId,
        String reviewedByDisplayName,
        String reviewComment,
        Instant reviewedAt,
        Instant createdAt) {
}
