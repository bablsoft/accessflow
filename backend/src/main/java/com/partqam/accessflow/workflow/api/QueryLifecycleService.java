package com.partqam.accessflow.workflow.api;

import com.partqam.accessflow.core.api.QueryStatus;

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
     * @throws com.partqam.accessflow.core.api.QueryRequestNotFoundException if no query exists
     *         with the given id (or it lives in a different organization).
     * @throws QueryNotCancellableException if the query is in a non-cancellable status.
     * @throws org.springframework.security.access.AccessDeniedException if the caller is not
     *         the submitter.
     */
    void cancel(CancelQueryCommand command);

    /**
     * Executes an {@code APPROVED} query through the proxy and persists the outcome.
     * Returns the resulting status ({@link QueryStatus#EXECUTED} on success or
     * {@link QueryStatus#FAILED} on execution error).
     *
     * @throws com.partqam.accessflow.core.api.QueryRequestNotFoundException if no query exists.
     * @throws QueryNotExecutableException if the query is not in {@code APPROVED}.
     * @throws org.springframework.security.access.AccessDeniedException if the caller is not
     *         the submitter and not an admin.
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
