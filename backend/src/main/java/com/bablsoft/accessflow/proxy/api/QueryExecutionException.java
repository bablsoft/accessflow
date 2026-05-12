package com.bablsoft.accessflow.proxy.api;

public sealed class QueryExecutionException extends RuntimeException
        permits QueryExecutionFailedException, QueryExecutionTimeoutException {

    protected QueryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
