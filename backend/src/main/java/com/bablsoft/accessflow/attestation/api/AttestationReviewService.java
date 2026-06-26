package com.bablsoft.accessflow.attestation.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.List;
import java.util.UUID;

/**
 * Reviewer-facing attestation operations: list the items the caller can act on, and certify / revoke
 * them. Eligibility (datasource reviewer scope, with org-admin fallback) and the self-review block
 * (a reviewer can never attest their own grant) are enforced here, not just at the controller layer.
 */
public interface AttestationReviewService {

    PageResponse<AttestationItemView> listPendingForReviewer(ReviewerContext context,
                                                             PageRequest pageRequest);

    ItemDecisionOutcome certify(UUID itemId, ReviewerContext context, String comment);

    ItemDecisionOutcome revoke(UUID itemId, ReviewerContext context, String comment);

    /**
     * Apply the same decision (CERTIFIED or REVOKED) to a list of items, evaluating each row
     * independently. A per-row failure never rolls back successful peers. Whole-batch validation
     * (empty list, oversized list, non-terminal decision) is the caller's responsibility.
     */
    BulkItemDecisionOutcome bulkDecide(List<UUID> itemIds, AttestationItemDecision decision,
                                       ReviewerContext context, String comment);

    record ReviewerContext(UUID userId, UUID organizationId, UserRoleType role) {
    }

    record ItemDecisionOutcome(UUID itemId, AttestationItemDecision decision,
                               boolean wasIdempotentReplay) {
    }

    record BulkItemDecisionOutcome(List<RowOutcome> rows) {
    }

    record RowOutcome(UUID itemId, RowStatus status, ItemDecisionOutcome outcome,
                      String errorCode, String errorMessage) {

        public static RowOutcome success(UUID itemId, ItemDecisionOutcome outcome) {
            return new RowOutcome(itemId, RowStatus.SUCCESS, outcome, null, null);
        }

        public static RowOutcome failure(UUID itemId, RowStatus status, String errorCode,
                                         String errorMessage) {
            return new RowOutcome(itemId, status, null, errorCode, errorMessage);
        }
    }

    enum RowStatus {
        SUCCESS,
        FORBIDDEN,
        INVALID_STATE,
        NOT_FOUND
    }
}
