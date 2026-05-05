package com.partqam.accessflow.workflow.api;

import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.UserRoleType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * Reviewer-facing operations: list pending queries, record approve/reject/request-changes
 * decisions, and drive the {@code PENDING_REVIEW → APPROVED|REJECTED} transitions of the
 * review workflow state machine. Self-approval is unconditionally blocked here, not just at
 * the controller layer.
 */
public interface ReviewService {

    Page<PendingReview> listPendingForReviewer(ReviewerContext context, Pageable pageable);

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
