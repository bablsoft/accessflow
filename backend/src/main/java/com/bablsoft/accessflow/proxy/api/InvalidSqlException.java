package com.bablsoft.accessflow.proxy.api;

public final class InvalidSqlException extends SqlParsingException {

    public InvalidSqlException(String message) {
        super(message);
    }

    public InvalidSqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
