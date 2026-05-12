package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.ReviewService.DecisionOutcome;

import java.util.UUID;

public record ReviewDecisionResponse(
        UUID queryRequestId,
        UUID decisionId,
        DecisionType decision,
        QueryStatus resultingStatus,
        boolean idempotentReplay) {

    public static ReviewDecisionResponse from(UUID queryRequestId, DecisionOutcome outcome) {
        return new ReviewDecisionResponse(
                queryRequestId,
                outcome.decisionId(),
                outcome.decision(),
                outcome.resultingStatus(),
                outcome.wasIdempotentReplay());
    }
}
