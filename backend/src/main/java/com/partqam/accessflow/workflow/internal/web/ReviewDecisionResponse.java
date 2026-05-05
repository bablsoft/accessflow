package com.partqam.accessflow.workflow.internal.web;

import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.workflow.api.ReviewService.DecisionOutcome;

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
