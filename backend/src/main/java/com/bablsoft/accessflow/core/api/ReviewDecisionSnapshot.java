package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A recorded review decision. {@code reviewerId} is always the acting human;
 * {@code onBehalfOfUserId} names the delegator whose authority was borrowed under an out-of-office
 * delegation (#622), and is null for a decision taken under the reviewer's own authority.
 */
public record ReviewDecisionSnapshot(
        UUID id,
        UUID queryRequestId,
        UUID reviewerId,
        DecisionType decision,
        String comment,
        int stage,
        Instant decidedAt,
        UUID onBehalfOfUserId,
        UUID delegationId) {

    /** Convenience constructor for a decision taken under the reviewer's own authority. */
    public ReviewDecisionSnapshot(UUID id, UUID queryRequestId, UUID reviewerId,
                                  DecisionType decision, String comment, int stage,
                                  Instant decidedAt) {
        this(id, queryRequestId, reviewerId, decision, comment, stage, decidedAt, null, null);
    }
}
