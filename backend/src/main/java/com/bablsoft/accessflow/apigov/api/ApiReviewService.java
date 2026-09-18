package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Reviewer-facing approve/reject of governed API requests. The submitter can never self-approve. */
public interface ApiReviewService {

    PageResponse<PendingApiReview> listPending(ReviewerContext context, PendingApiReviewFilter filter,
                                               PageRequest pageRequest);

    DecisionOutcome approve(UUID apiRequestId, ReviewerContext context, String comment);

    DecisionOutcome reject(UUID apiRequestId, ReviewerContext context, String comment);

    record ReviewerContext(UUID userId, UUID organizationId, String roleName,
                           Set<Permission> permissions) {
    }

    /** Optional narrowing of the pending-review queue. Both fields are nullable / AND-combined. */
    record PendingApiReviewFilter(UUID connectorId, String verb) {
    }

    record PendingApiReview(
            UUID apiRequestId, UUID connectorId, String connectorName, UUID submittedByUserId,
            String verb, String requestPath, boolean write, String justification, UUID aiAnalysisId,
            RiskLevel aiRiskLevel, Integer aiRiskScore, String aiSummary, int currentStage,
            /**
             * AF-613: how many connector variables this submitter overrode. A count rather than
             * the values, so the queue can badge affected requests without joining the body; the
             * values themselves are on the detail view.
             */
            int variableOverrideCount,
            Instant createdAt,
            /** The human an API-key submitter acted for (#874); null for a human submission. */
            UUID onBehalfOfUserId) {

        /** Backward-compatible constructor without the #874 on-behalf-of principal. */
        public PendingApiReview(UUID apiRequestId, UUID connectorId, String connectorName,
                                UUID submittedByUserId, String verb, String requestPath, boolean write,
                                String justification, UUID aiAnalysisId, RiskLevel aiRiskLevel,
                                Integer aiRiskScore, String aiSummary, int currentStage,
                                int variableOverrideCount, Instant createdAt) {
            this(apiRequestId, connectorId, connectorName, submittedByUserId, verb, requestPath, write,
                    justification, aiAnalysisId, aiRiskLevel, aiRiskScore, aiSummary, currentStage,
                    variableOverrideCount, createdAt, null);
        }
    }

    record DecisionOutcome(UUID decisionId, DecisionType decision, QueryStatus resultingStatus,
                           boolean wasIdempotentReplay) {
    }
}
