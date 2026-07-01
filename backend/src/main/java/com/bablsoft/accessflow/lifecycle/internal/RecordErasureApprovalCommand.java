package com.bablsoft.accessflow.lifecycle.internal;

import java.util.UUID;

/**
 * Input to {@code ErasureRequestStateService.recordApprovalAndAdvance}. Carries the per-stage
 * threshold and last-stage flag so the state service can transition to {@code APPROVED} when the
 * final stage is satisfied without re-loading plan state. Mirrors {@code RecordAccessApprovalCommand}.
 */
record RecordErasureApprovalCommand(
        UUID requestId,
        UUID reviewerId,
        int stage,
        int minApprovalsRequired,
        boolean isLastStage,
        String comment) {
}
