package com.bablsoft.accessflow.lifecycle.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.UUID;

/**
 * Review queue for right-to-erasure requests (AF-519 — review-plan based, REVIEWER-eligible). The
 * submitter can never decide their own request ({@link ErasureSelfApprovalException}). Reviewer
 * eligibility follows the datasource's review plan and scoped-reviewer set, with an ADMIN backstop;
 * a non-eligible caller gets {@link ErasureReviewerNotEligibleException}. Multi-stage plans are
 * honoured — only the final stage transitions a {@link ErasureStatus#PENDING_REVIEW} request to
 * {@link ErasureStatus#APPROVED}; rejection transitions to {@link ErasureStatus#REJECTED}.
 */
public interface ErasureReviewService {

    /**
     * Lists requests the caller can currently act on: admins see every pending request (excluding
     * their own); a plan-eligible reviewer sees requests routed to them at the current stage.
     */
    PageResponse<ErasureRequestView> listPending(ReviewerContext context, PageRequest pageRequest);

    ErasureRequestView approve(UUID requestId, ReviewerContext reviewer, String comment);

    ErasureRequestView reject(UUID requestId, ReviewerContext reviewer, String comment);

    record ReviewerContext(UUID reviewerId, UUID organizationId, UserRoleType role) {
    }
}
