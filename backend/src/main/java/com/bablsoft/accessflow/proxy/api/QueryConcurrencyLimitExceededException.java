package com.bablsoft.accessflow.proxy.api;

/**
 * Thrown when the global query-execution concurrency budget (issue #49) cannot admit another
 * in-flight execution within the configured acquire timeout. Mapped to HTTP 503.
 */
public final class QueryConcurrencyLimitExceededException extends RuntimeException {

    public QueryConcurrencyLimitExceededException(String message) {
        super(message);
    }
}
