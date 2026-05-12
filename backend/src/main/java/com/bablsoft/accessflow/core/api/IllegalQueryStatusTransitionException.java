package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class IllegalQueryStatusTransitionException extends RuntimeException {

    private final UUID queryRequestId;
    private final QueryStatus actual;
    private final QueryStatus expected;

    public IllegalQueryStatusTransitionException(UUID queryRequestId, QueryStatus actual,
                                                 QueryStatus expected) {
        super("Query " + queryRequestId + " is " + actual + ", expected " + expected);
        this.queryRequestId = queryRequestId;
        this.actual = actual;
        this.expected = expected;
    }

    public UUID queryRequestId() {
        return queryRequestId;
    }

    public QueryStatus actual() {
        return actual;
    }

    public QueryStatus expected() {
        return expected;
    }
}
