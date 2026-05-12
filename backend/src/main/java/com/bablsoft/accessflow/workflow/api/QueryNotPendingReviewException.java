package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

public final class QueryNotPendingReviewException extends RuntimeException {

    public QueryNotPendingReviewException(UUID queryRequestId, QueryStatus actual) {
        super("Query " + queryRequestId + " is not pending review (status=" + actual + ")");
    }
}
