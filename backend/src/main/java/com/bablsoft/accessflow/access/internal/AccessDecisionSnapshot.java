package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.core.api.DecisionType;

import java.time.Instant;
import java.util.UUID;

record AccessDecisionSnapshot(
        UUID id,
        UUID accessRequestId,
        UUID reviewerId,
        DecisionType decision,
        String comment,
        int stage,
        Instant decidedAt) {
}
