package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin "Break-glass log" row (AF-385): a single emergency execution and its retro-review state.
 * {@code executionStatus} is the executed query's terminal status (EXECUTED / FAILED), distinct
 * from {@code status} which is the retro-review lifecycle. Exactly one of {@code queryRequestId} /
 * {@code apiRequestId} / {@code deploymentRequestId} is set — the row's target kind (AF-500 added
 * API targets, #692 deployment targets); {@code connectorId} / {@code pipelineId} name the
 * governed resource for the non-query kinds the way {@code datasourceId} does for queries.
 */
public record BreakGlassEventView(
        UUID id,
        UUID queryRequestId,
        UUID apiRequestId,
        UUID deploymentRequestId,
        UUID organizationId,
        UUID datasourceId,
        String datasourceName,
        UUID connectorId,
        UUID pipelineId,
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
