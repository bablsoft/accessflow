package com.bablsoft.accessflow.access.internal;

import java.util.UUID;

/**
 * Input to {@code AccessGrantRequestStateService.recordApprovalAndAdvance}. Carries the per-stage
 * threshold and last-stage flag so the implementation can transition to {@code APPROVED} when the
 * final stage is satisfied without re-loading plan state. Mirrors {@code RecordApprovalCommand}.
 */
record RecordAccessApprovalCommand(
        UUID accessRequestId,
        UUID reviewerId,
        int stage,
        int minApprovalsRequired,
        boolean isLastStage,
        String comment) {
}
