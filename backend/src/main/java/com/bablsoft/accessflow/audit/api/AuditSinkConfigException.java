package com.bablsoft.accessflow.audit.api;

/** A sink's {@code config} is missing required keys or carries invalid values (HTTP 422). */
public class AuditSinkConfigException extends RuntimeException {

    public AuditSinkConfigException(String message) {
        super(message);
    }

    public AuditSinkConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
