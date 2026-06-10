package com.bablsoft.accessflow.core.api;

public sealed class SqlParsingException extends RuntimeException
        permits InvalidSqlException {

    protected SqlParsingException(String message) {
        super(message);
    }

    protected SqlParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
