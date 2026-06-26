package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ItemDecisionOutcome;

import java.util.UUID;

public record AttestationDecisionResponse(
        UUID itemId,
        AttestationItemDecision decision,
        boolean wasIdempotentReplay) {

    public static AttestationDecisionResponse from(ItemDecisionOutcome outcome) {
        return new AttestationDecisionResponse(outcome.itemId(), outcome.decision(),
                outcome.wasIdempotentReplay());
    }
}
