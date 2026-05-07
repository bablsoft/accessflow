package com.partqam.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Atomic state-machine and decision-recording primitives over {@code query_requests}. All
 * mutating methods take a pessimistic write lock on the target row so the read-decide-write
 * sequence is serialized per query under concurrent reviewers.
 */
public interface QueryRequestStateService {

    /**
     * Guarded transition. Throws {@link IllegalQueryStatusTransitionException} if the current
     * status is not {@code expected}.
     */
    void transitionTo(UUID queryRequestId, QueryStatus expected, QueryStatus next);

    /**
     * Inserts an {@code APPROVED} {@link com.partqam.accessflow.core.api.DecisionType} row
     * for the given reviewer/stage and, if the per-stage threshold is now met AND it was the
     * last stage, transitions {@code PENDING_REVIEW → APPROVED} in the same transaction.
     *
     * <p>Idempotent: if a decision already exists for {@code (queryRequestId, reviewerId, stage)}
     * (the V11 unique index), the existing row is returned with {@code wasIdempotentReplay=true}
     * and no transition is attempted.
     */
    RecordDecisionResult recordApprovalAndAdvance(RecordApprovalCommand command);

    /**
     * Inserts a {@code REJECTED} decision for the given reviewer/stage and transitions
     * {@code PENDING_REVIEW → REJECTED} in the same transaction.
     */
    RecordDecisionResult recordRejection(UUID queryRequestId, UUID reviewerId, int stage,
                                         String comment);

    /**
     * Inserts a {@code REQUESTED_CHANGES} decision for the given reviewer/stage and leaves the
     * status at {@code PENDING_REVIEW}.
     */
    RecordDecisionResult recordChangesRequested(UUID queryRequestId, UUID reviewerId, int stage,
                                                String comment);

    List<ReviewDecisionSnapshot> listDecisions(UUID queryRequestId);

    /**
     * Atomically records the outcome of executing an {@code APPROVED} query: transitions to
     * {@link QueryStatus#EXECUTED} or {@link QueryStatus#FAILED}, sets {@code rowsAffected},
     * {@code executionDurationMs}, {@code errorMessage}, and execution timestamps.
     *
     * @throws IllegalQueryStatusTransitionException if the current status is not
     *         {@link QueryStatus#APPROVED}.
     */
    void recordExecutionOutcome(RecordExecutionCommand command);
}
