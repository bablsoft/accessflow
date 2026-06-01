package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessReviewService.DecisionOutcome;
import com.bablsoft.accessflow.core.api.DecisionType;

import java.util.UUID;

public record AccessDecisionResponse(
        UUID accessRequestId,
        UUID decisionId,
        DecisionType decision,
        AccessGrantStatus resultingStatus,
        boolean idempotentReplay) {

    public static AccessDecisionResponse from(UUID accessRequestId, DecisionOutcome outcome) {
        return new AccessDecisionResponse(
                accessRequestId,
                outcome.decisionId(),
                outcome.decision(),
                outcome.resultingStatus(),
                outcome.wasIdempotentReplay());
    }
}
