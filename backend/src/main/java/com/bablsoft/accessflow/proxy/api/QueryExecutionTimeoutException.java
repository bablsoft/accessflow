package com.bablsoft.accessflow.proxy.api;

import java.time.Duration;

public final class QueryExecutionTimeoutException extends QueryExecutionException {

    private final Duration timeout;

    public QueryExecutionTimeoutException(String message, Duration timeout, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }
}
