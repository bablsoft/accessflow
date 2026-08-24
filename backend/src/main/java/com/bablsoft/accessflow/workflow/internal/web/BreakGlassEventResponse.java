package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventView;
import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * API response for a break-glass log row (AF-385). Field names are snake_case over the wire.
 * Exactly one of {@code queryRequestId} / {@code apiRequestId} / {@code deploymentRequestId} is
 * set — the row's target kind (AF-500 / #692).
 */
public record BreakGlassEventResponse(
        UUID id,
        UUID queryRequestId,
        UUID apiRequestId,
        UUID deploymentRequestId,
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

    public static BreakGlassEventResponse from(BreakGlassEventView view) {
        return new BreakGlassEventResponse(
                view.id(),
                view.queryRequestId(),
                view.apiRequestId(),
                view.deploymentRequestId(),
                view.datasourceId(),
                view.datasourceName(),
                view.connectorId(),
                view.pipelineId(),
                view.submittedByUserId(),
                view.submittedByDisplayName(),
                view.submittedByEmail(),
                view.sqlText(),
                view.executionStatus(),
                view.justification(),
                view.status(),
                view.reviewedByUserId(),
                view.reviewedByDisplayName(),
                view.reviewComment(),
                view.reviewedAt(),
                view.createdAt());
    }
}
