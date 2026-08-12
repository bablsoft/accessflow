package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Owns the post-submission state changes that aren't part of the review workflow:
 * cancellation by the submitter, and manual execution of an already-approved query.
 */
public interface QueryLifecycleService {

    /**
     * Transitions the query to {@link QueryStatus#CANCELLED}. Only the submitter may cancel —
     * except a recurring series (#627, {@code recurrence_rule} set), which any caller with
     * review authority ({@code callerIsReviewer}) may also cancel as a kill-switch. Cancellable
     * while the query is in {@code PENDING_AI} or {@code PENDING_REVIEW}, or {@code APPROVED}
     * with a pending {@code scheduled_for} or a recurrence rule.
     *
     * <p>Authorization is enforced by the security layer; a denial is translated to HTTP 403
     * by the global exception handler.
     *
     * @throws com.bablsoft.accessflow.core.api.QueryRequestNotFoundException if no query exists
     *         with the given id (or it lives in a different organization).
     * @throws QueryNotCancellableException if the query is in a non-cancellable status.
     */
    void cancel(CancelQueryCommand command);

    /**
     * Executes an {@code APPROVED} query through the proxy and persists the outcome.
     * Returns the resulting status ({@link QueryStatus#EXECUTED} on success or
     * {@link QueryStatus#FAILED} on execution error).
     *
     * <p>Authorization is enforced by the security layer; a denial is translated to HTTP 403
     * by the global exception handler.
     *
     * @throws com.bablsoft.accessflow.core.api.QueryRequestNotFoundException if no query exists.
     * @throws QueryNotExecutableException if the query is not in {@code APPROVED}.
     */
    ExecutionOutcome execute(ExecuteQueryCommand command);

    /**
     * Executes an already-{@code APPROVED} break-glass query (AF-385). Identical to a normal
     * execution through the proxy guards, but records a prominent {@code QUERY_BREAK_GLASS_EXECUTED}
     * audit action instead of {@code QUERY_EXECUTED}. Called by {@link BreakGlassService} after it
     * force-approves the query; not exposed as its own endpoint.
     *
     * @throws com.bablsoft.accessflow.core.api.QueryRequestNotFoundException if no query exists.
     * @throws QueryNotExecutableException if the query is not in {@code APPROVED}.
     */
    ExecutionOutcome executeBreakGlass(UUID queryRequestId, UUID actorUserId);

    /**
     * System-triggered execution of a query whose {@code scheduled_for} timestamp is at or before
     * the current instant. Bypasses the per-user ownership check because the actor is the
     * scheduler, not a request principal — the submitter is recorded as the audit actor and the
     * audit metadata carries {@code trigger=scheduled}.
     *
     * <p>Idempotent: silently returns if the query is no longer {@code APPROVED} (a manual
     * execution or cancellation may have raced the job) or if {@code scheduled_for} is null /
     * still in the future (the row should not yet be due).
     *
     * @throws com.bablsoft.accessflow.core.api.QueryRequestNotFoundException if the query is gone.
     */
    void executeScheduled(UUID queryRequestId);

    /**
     * System-triggered execution of the next occurrence of an approved recurring series (#627).
     * Re-evaluates the submitter's permission (existence, expiry, capability, table allow-list)
     * and the datasource's active flag with <em>current</em> state, re-parsing the SQL first so a
     * parse failure can never vacuously pass the allow-list — any failure <strong>halts the series
     * fail-closed</strong> (cursor cleared, {@code recurrence_halted_reason} persisted, audited as
     * {@code RECURRING_SERIES_HALTED}) rather than retrying every tick. On success a child
     * occurrence row is created directly in {@code APPROVED} (advancing the cursor atomically) and
     * executed through the full proxy pipeline; the parent stays {@code APPROVED}. Once
     * {@code recurrence_until} passes, the cursor is cleared with no halt reason — the derived
     * "series completed" state.
     *
     * <p>Idempotent: silently returns if the parent is no longer {@code APPROVED}, has no
     * recurrence rule, or its cursor is null / still in the future.
     *
     * @throws com.bablsoft.accessflow.core.api.QueryRequestNotFoundException if the parent is gone.
     */
    void executeRecurringOccurrence(UUID parentQueryRequestId);

    /**
     * Re-runs AI analysis on a query whose previous analysis failed. Restricted to reviewers and
     * admins by the security layer. The pre-existing failed {@code ai_analyses} row is removed
     * and {@link com.bablsoft.accessflow.ai.api.AiAnalyzerService#analyzeSubmittedQuery(UUID)} is
     * invoked again, which inserts a fresh row and publishes the usual completion / failure
     * events.
     *
     * @throws com.bablsoft.accessflow.core.api.QueryRequestNotFoundException if the query is not
     *         found in the caller's organization.
     * @throws QueryNotReanalyzableException if the query is not in {@code PENDING_REVIEW} or
     *         the linked analysis is not marked failed.
     */
    void reanalyze(ReanalyzeQueryCommand command);

    record CancelQueryCommand(UUID queryRequestId, UUID callerUserId, UUID callerOrganizationId,
                              boolean callerIsReviewer) {

        /** Backward-compatible constructor without the #627 reviewer kill-switch flag. */
        public CancelQueryCommand(UUID queryRequestId, UUID callerUserId,
                                  UUID callerOrganizationId) {
            this(queryRequestId, callerUserId, callerOrganizationId, false);
        }
    }

    record ExecuteQueryCommand(UUID queryRequestId, UUID callerUserId, UUID callerOrganizationId,
                               boolean isAdmin) {
    }

    record ReanalyzeQueryCommand(UUID queryRequestId, UUID callerUserId,
                                 UUID callerOrganizationId) {
    }

    record ExecutionOutcome(UUID queryRequestId, QueryStatus status,
                            Long rowsAffected, Integer durationMs) {
    }
}
