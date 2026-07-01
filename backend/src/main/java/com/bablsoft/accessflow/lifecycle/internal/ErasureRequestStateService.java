package com.bablsoft.accessflow.lifecycle.internal;

import java.util.List;
import java.util.UUID;

/**
 * Atomic state-machine and decision-recording primitive over {@code deletion_requests} (AF-519). All
 * mutating methods take a pessimistic write lock on the target row so the read-decide-write sequence
 * is serialized under concurrent reviewers and the timeout job. Sole owner of the {@code status}
 * column for review transitions ({@code PENDING_REVIEW → APPROVED/REJECTED}) and idempotent on
 * {@code (request_id, reviewer_id, stage)}. Mirrors {@code AccessGrantRequestStateService}; stays
 * in {@code lifecycle.internal} (module-private by Modulith convention; {@code public} only so the
 * {@code internal.scheduled} timeout job can inject it — no cross-module sharing).
 */
public interface ErasureRequestStateService {

    RecordErasureDecisionResult recordApprovalAndAdvance(RecordErasureApprovalCommand command);

    RecordErasureDecisionResult recordRejection(UUID requestId, UUID reviewerId, int stage,
                                                String comment);

    List<ErasureDecisionSnapshot> listDecisions(UUID requestId);

    /**
     * Auto-rejects a request stuck in {@code PENDING_REVIEW} past the review timeout: transitions it
     * to {@code REJECTED} with a "review timeout" failure reason and records no decision row.
     * Idempotent — returns {@code false} when the row is no longer {@code PENDING_REVIEW}.
     */
    boolean markTimedOut(UUID requestId);
}
