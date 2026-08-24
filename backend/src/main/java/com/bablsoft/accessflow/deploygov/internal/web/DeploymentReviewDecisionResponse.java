package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewDecisionView;

import java.time.Instant;
import java.util.UUID;

public record DeploymentReviewDecisionResponse(
        UUID id, UUID reviewerId, DecisionType decision, String comment, int stage,
        Instant decidedAt) {

    static DeploymentReviewDecisionResponse from(DeploymentReviewDecisionView view) {
        return new DeploymentReviewDecisionResponse(view.id(), view.reviewerId(), view.decision(),
                view.comment(), view.stage(), view.decidedAt());
    }
}
