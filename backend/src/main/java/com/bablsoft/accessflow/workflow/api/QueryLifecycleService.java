package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Owns the post-submission state changes that aren't part of the review workflow:
 * cancellation by the submitter, and manual execution of an already-approved query.
 */
public interface QueryLifecycleService {

    /**
     * Transitions the query to {@link QueryStatus#CANCELLED}. Only the submitter may cancel,
     * and only while the query is in {@code PENDING_AI} or {@code PENDING_REVIEW}.
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

    record CancelQueryCommand(UUID queryRequestId, UUID callerUserId, UUID callerOrganizationId) {
    }

    record ExecuteQueryCommand(UUID queryRequestId, UUID callerUserId, UUID callerOrganizationId,
                               boolean isAdmin) {
    }

    record ExecutionOutcome(UUID queryRequestId, QueryStatus status,
                            Long rowsAffected, Integer durationMs) {
    }
}
