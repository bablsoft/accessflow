package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.List;
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

    /**
     * Apply the same decision to a list of queries, evaluating each row independently. A
     * per-row failure (forbidden, wrong state, not found) never rolls back successful peers.
     * Each successful row publishes the same events as the single-row methods so audit and
     * notifications continue to work unchanged. Whole-batch validation (empty list, missing
     * required comment, oversized list) must be enforced by the caller before invoking.
     */
    BulkDecisionOutcome bulkDecide(List<UUID> queryRequestIds, DecisionType decision,
                                   ReviewerContext context, String comment);

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

    record BulkDecisionOutcome(List<RowOutcome> rows) {
    }

    /**
     * Per-row result of a {@link #bulkDecide} call. Exactly one of {@code outcome} (on
     * SUCCESS) or {@code errorCode}/{@code errorMessage} (on failure) is populated.
     */
    record RowOutcome(UUID queryRequestId, RowStatus status, DecisionOutcome outcome,
                      String errorCode, String errorMessage) {

        public static RowOutcome success(UUID queryRequestId, DecisionOutcome outcome) {
            return new RowOutcome(queryRequestId, RowStatus.SUCCESS, outcome, null, null);
        }

        public static RowOutcome failure(UUID queryRequestId, RowStatus status,
                                         String errorCode, String errorMessage) {
            return new RowOutcome(queryRequestId, status, null, errorCode, errorMessage);
        }
    }

    enum RowStatus {
        SUCCESS,
        FORBIDDEN,
        INVALID_STATE,
        NOT_FOUND
    }
}
