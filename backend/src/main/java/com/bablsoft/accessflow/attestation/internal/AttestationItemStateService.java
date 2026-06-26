package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ItemDecisionOutcome;

import java.util.UUID;

/**
 * Atomic decision primitive over {@code attestation_item}. Both methods take a pessimistic write lock
 * on the target row so the read-decide-write sequence is serialized across concurrent reviewers and
 * the close job, and both are idempotent — a second call on an already-terminal item returns a replay
 * outcome without mutating. {@code reviewerId} is null for the end-of-campaign automatic default.
 */
interface AttestationItemStateService {

    ItemDecisionOutcome certify(UUID itemId, UUID reviewerId, String comment,
                                AttestationItemCloseReason reason);

    /** Revokes the underlying permission (tolerating already-gone), then marks the item REVOKED. */
    ItemDecisionOutcome revoke(UUID itemId, UUID reviewerId, String comment,
                               AttestationItemCloseReason reason);
}
