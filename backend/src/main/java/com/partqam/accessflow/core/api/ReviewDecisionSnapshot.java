package com.partqam.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record ReviewDecisionSnapshot(
        UUID id,
        UUID queryRequestId,
        UUID reviewerId,
        DecisionType decision,
        String comment,
        int stage,
        Instant decidedAt) {
}
