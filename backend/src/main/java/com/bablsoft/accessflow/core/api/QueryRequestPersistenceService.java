package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists a new {@code query_requests} row in {@link QueryStatus#PENDING_AI}. Used by the workflow
 * module to submit queries without reaching into {@code core/internal} JPA entities.
 */
public interface QueryRequestPersistenceService {

    UUID submit(SubmitQueryCommand command);

    /**
     * Creates the next occurrence row of an approved recurring series (#627) and advances the
     * parent's {@code recurrence_next_run_at} cursor to {@code nextRunAt} ({@code null} marks the
     * final occurrence) — both in one transaction, under a pessimistic lock on the parent, so a
     * crashed executor can never double-fire the same occurrence. The child copies the parent's
     * datasource, submitter, SQL, query type, transactional flag, and justification; it is created
     * directly in {@link QueryStatus#APPROVED} with {@link SubmissionReason#RECURRING} and
     * {@code recurring_parent_id} set — an insert, not a state transition, and no submission event
     * is published. Returns empty (creating nothing) when the parent is no longer an active series
     * — not {@code APPROVED}, or its cursor was cleared by a raced cancel / halt.
     */
    Optional<UUID> createRecurringOccurrence(UUID parentId, Instant nextRunAt);

    /**
     * Clears an approved recurring series' {@code recurrence_next_run_at} cursor so the recurring
     * job never picks it up again (#627). A {@code null} {@code haltedReason} marks clean series
     * completion ({@code recurrence_until} passed); a non-null reason marks a fail-closed halt
     * (permission lost, SQL unparseable, datasource deactivated) and is persisted on
     * {@code recurrence_halted_reason} for the UI. Idempotent.
     */
    void clearRecurrenceNextRun(UUID parentId, String haltedReason);
}
