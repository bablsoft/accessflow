package com.partqam.accessflow.workflow.api;

import com.partqam.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Thrown when a caller attempts to cancel a query that is no longer cancellable. Cancellation
 * is permitted only while the query is in {@code PENDING_AI} or {@code PENDING_REVIEW}.
 */
public final class QueryNotCancellableException extends RuntimeException {

    private final UUID queryRequestId;
    private final QueryStatus currentStatus;

    public QueryNotCancellableException(UUID queryRequestId, QueryStatus currentStatus) {
        super("Query " + queryRequestId + " is not cancellable in status " + currentStatus);
        this.queryRequestId = queryRequestId;
        this.currentStatus = currentStatus;
    }

    public UUID queryRequestId() {
        return queryRequestId;
    }

    public QueryStatus currentStatus() {
        return currentStatus;
    }
}
