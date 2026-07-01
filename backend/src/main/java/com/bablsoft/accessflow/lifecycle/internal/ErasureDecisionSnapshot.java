package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.ErasureDecision;

import java.time.Instant;
import java.util.UUID;

/** A recorded erasure-review decision, used to compute the current review stage. */
record ErasureDecisionSnapshot(
        UUID id,
        UUID requestId,
        UUID reviewerId,
        ErasureDecision decision,
        String comment,
        int stage,
        Instant createdAt) {
}
