package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reviewer/admin-facing operations on access-grant requests. Reuses the same reviewer-eligibility +
 * multi-stage approval machinery as query review: a requester can never approve their own request
 * (enforced here, not just in the UI).
 */
public interface AccessReviewService {

    PageResponse<PendingAccessRequest> listPendingForReviewer(ReviewerContext context,
                                                              PageRequest pageRequest);

    DecisionOutcome approve(UUID accessRequestId, ReviewerContext context, String comment);

    DecisionOutcome reject(UUID accessRequestId, ReviewerContext context, String comment);

    /**
     * Admin early-revoke of an active (APPROVED) grant: revokes the materialised permission and
     * transitions the request to {@code REVOKED}. Idempotent — {@code wasNoOp=true} when the grant
     * was already inactive.
     */
    RevocationOutcome revoke(UUID accessRequestId, ReviewerContext context, String comment);

    record ReviewerContext(UUID userId, UUID organizationId, UserRoleType role) {
    }

    record PendingAccessRequest(
            UUID id,
            UUID datasourceId,
            String datasourceName,
            UUID requesterId,
            String requesterEmail,
            boolean canRead,
            boolean canWrite,
            boolean canDdl,
            List<String> allowedSchemas,
            List<String> allowedTables,
            String requestedDuration,
            String justification,
            boolean preApproveQueries,
            int currentStage,
            Instant createdAt) {
    }

    record DecisionOutcome(
            UUID decisionId,
            DecisionType decision,
            AccessGrantStatus resultingStatus,
            boolean wasIdempotentReplay) {
    }

    record RevocationOutcome(AccessGrantStatus resultingStatus, boolean wasNoOp) {
    }
}
