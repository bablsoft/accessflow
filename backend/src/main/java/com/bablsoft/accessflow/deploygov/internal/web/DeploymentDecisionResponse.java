package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;

import java.util.UUID;

public record DeploymentDecisionResponse(UUID decisionId, DecisionType decision,
                                         QueryStatus resultingStatus, boolean duplicate) {

    static DeploymentDecisionResponse from(DeploymentReviewService.DecisionOutcome o) {
        return new DeploymentDecisionResponse(o.decisionId(), o.decision(), o.resultingStatus(),
                o.duplicate());
    }
}
