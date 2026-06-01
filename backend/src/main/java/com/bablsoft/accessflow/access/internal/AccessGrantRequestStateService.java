package com.bablsoft.accessflow.access.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Atomic state-machine and decision-recording primitive over {@code access_grant_request}. All
 * mutating methods take a pessimistic write lock on the target row so the read-decide-write
 * sequence is serialized under concurrent reviewers and the expiry job. Sole owner of the
 * {@code status} column and of {@code access_grant_request → status} transitions; publishes
 * {@code AccessRequestStatusChangedEvent} on every transition. Mirrors
 * {@code QueryRequestStateService}.
 */
interface AccessGrantRequestStateService {

    RecordAccessDecisionResult recordApprovalAndAdvance(RecordAccessApprovalCommand command);

    RecordAccessDecisionResult recordRejection(UUID accessRequestId, UUID reviewerId, int stage,
                                               String comment);

    List<AccessDecisionSnapshot> listDecisions(UUID accessRequestId);

    /** Stores the materialised permission id + expiry on an already-APPROVED request. */
    void attachGrant(UUID accessRequestId, UUID permissionId, Instant expiresAt);

    /** Transitions {@code PENDING → CANCELLED}. Throws when not pending. */
    void cancel(UUID accessRequestId);

    /**
     * Revokes the materialised permission and transitions {@code APPROVED → EXPIRED}. Idempotent:
     * returns {@code false} when the row is no longer APPROVED.
     */
    boolean expire(UUID accessRequestId);

    /**
     * Revokes the materialised permission and transitions {@code APPROVED → REVOKED}. Idempotent:
     * returns {@code false} when the row is no longer APPROVED.
     */
    boolean revoke(UUID accessRequestId, UUID revokedByUserId);
}
