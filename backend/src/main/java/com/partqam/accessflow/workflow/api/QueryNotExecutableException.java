package com.partqam.accessflow.workflow.api;

import com.partqam.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Thrown when a caller attempts to manually execute a query that is not in {@code APPROVED}.
 */
public final class QueryNotExecutableException extends RuntimeException {

    private final UUID queryRequestId;
    private final QueryStatus currentStatus;

    public QueryNotExecutableException(UUID queryRequestId, QueryStatus currentStatus) {
        super("Query " + queryRequestId + " is not executable in status " + currentStatus);
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
