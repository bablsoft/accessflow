package com.bablsoft.accessflow.lifecycle.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Admin review queue for right-to-erasure requests. The submitter can never decide their own request
 * ({@link ErasureSelfApprovalException}). Approval transitions a {@link ErasureStatus#PENDING_REVIEW}
 * request to {@link ErasureStatus#APPROVED}; rejection to {@link ErasureStatus#REJECTED}.
 */
public interface ErasureReviewService {

    /** Lists requests awaiting review in the org, excluding the caller's own submissions. */
    PageResponse<ErasureRequestView> listPending(UUID organizationId, UUID reviewerId,
                                                 PageRequest pageRequest);

    ErasureRequestView approve(UUID requestId, ReviewerContext reviewer, String comment);

    ErasureRequestView reject(UUID requestId, ReviewerContext reviewer, String comment);

    record ReviewerContext(UUID reviewerId, UUID organizationId) {
    }
}
