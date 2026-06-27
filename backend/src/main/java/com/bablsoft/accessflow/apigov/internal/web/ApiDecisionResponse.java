package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiReviewService;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

public record ApiDecisionResponse(UUID decisionId, DecisionType decision, QueryStatus resultingStatus,
                                  boolean wasIdempotentReplay) {

    static ApiDecisionResponse from(ApiReviewService.DecisionOutcome o) {
        return new ApiDecisionResponse(o.decisionId(), o.decision(), o.resultingStatus(),
                o.wasIdempotentReplay());
    }
}
