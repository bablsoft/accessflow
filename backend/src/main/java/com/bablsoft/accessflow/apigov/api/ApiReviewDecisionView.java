package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.DecisionType;

import java.time.Instant;
import java.util.UUID;

public record ApiReviewDecisionView(
        UUID id, UUID reviewerId, DecisionType decision, String comment, int stage, Instant decidedAt) {
}
