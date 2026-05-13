package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.UUID;

/**
 * Reviewer-facing operations: list pending queries, record approve/reject/request-changes
 * decisions, and drive the {@code PENDING_REVIEW → APPROVED|REJECTED} transitions of the
 * review workflow state machine. Self-approval is unconditionally blocked here, not just at
 * the controller layer.
 */
public interface ReviewService {

    PageResponse<PendingReview> listPendingForReviewer(ReviewerContext context,
                                                       PageRequest pageRequest);

    DecisionOutcome approve(UUID queryRequestId, ReviewerContext context, String comment);

    DecisionOutcome reject(UUID queryRequestId, ReviewerContext context, String comment);

    DecisionOutcome requestChanges(UUID queryRequestId, ReviewerContext context, String comment);

    record ReviewerContext(UUID userId, UUID organizationId, UserRoleType role) {
    }

    record PendingReview(
            UUID queryRequestId,
            UUID datasourceId,
            String datasourceName,
            UUID submittedByUserId,
            String submittedByEmail,
            String sqlText,
            QueryType queryType,
            String justification,
            UUID aiAnalysisId,
            RiskLevel aiRiskLevel,
            Integer aiRiskScore,
            String aiSummary,
            int currentStage,
            Instant createdAt) {
    }

    record DecisionOutcome(UUID decisionId, DecisionType decision, QueryStatus resultingStatus,
                           boolean wasIdempotentReplay) {
    }
}
