package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Input to {@code QueryRequestStateService.recordApprovalAndAdvance}. Carries the per-stage
 * threshold and last-stage flag so the implementation can transition to {@code APPROVED} when
 * the final stage is satisfied without re-loading plan state.
 */
public record RecordApprovalCommand(
        UUID queryRequestId,
        UUID reviewerId,
        int stage,
        int minApprovalsRequired,
        boolean isLastStage,
        String comment) {
}
