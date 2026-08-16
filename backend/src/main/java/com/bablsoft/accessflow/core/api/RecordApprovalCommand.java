package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Input to {@code QueryRequestStateService.recordApprovalAndAdvance}. Carries the per-stage
 * threshold and last-stage flag so the implementation can transition to {@code APPROVED} when
 * the final stage is satisfied without re-loading plan state.
 *
 * <p>{@code onBehalfOfUserId} and {@code delegationId} record an out-of-office delegation (#622):
 * {@code reviewerId} stays the acting human — which is what keeps one human to one vote under the
 * {@code UNIQUE (query_request_id, reviewer_id, stage)} index — while these name whose authority
 * was borrowed and under which grant. Both are null when the reviewer was eligible in their own
 * right.
 */
public record RecordApprovalCommand(
        UUID queryRequestId,
        UUID reviewerId,
        int stage,
        int minApprovalsRequired,
        boolean isLastStage,
        String comment,
        UUID onBehalfOfUserId,
        UUID delegationId) {

    /** Convenience constructor for a decision the reviewer took under their own authority. */
    public RecordApprovalCommand(UUID queryRequestId, UUID reviewerId, int stage,
                                 int minApprovalsRequired, boolean isLastStage, String comment) {
        this(queryRequestId, reviewerId, stage, minApprovalsRequired, isLastStage, comment,
                null, null);
    }
}
