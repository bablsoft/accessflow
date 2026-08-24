package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.DecisionType;

import java.time.Instant;
import java.util.UUID;

/** One reviewer's decision on a deployment request. Populated by the review flow (#692). */
public record DeploymentReviewDecisionView(
        UUID id, UUID reviewerId, DecisionType decision, String comment, int stage, Instant decidedAt) {
}
