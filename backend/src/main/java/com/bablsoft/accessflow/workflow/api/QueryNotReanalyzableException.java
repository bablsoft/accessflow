package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Thrown when a caller attempts to trigger an AI re-analysis on a query that is not in a
 * re-analyzable state — either the previous AI analysis did not fail, or the query has moved
 * past {@code PENDING_REVIEW}.
 */
public final class QueryNotReanalyzableException extends RuntimeException {

    private final UUID queryRequestId;
    private final QueryStatus currentStatus;

    public QueryNotReanalyzableException(UUID queryRequestId, QueryStatus currentStatus) {
        super("Query " + queryRequestId + " is not re-analyzable in status " + currentStatus);
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
