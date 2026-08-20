package com.bablsoft.accessflow.audit.api;

/** A synthetic test delivery through a sink failed (HTTP 502). */
public class AuditSinkTestFailedException extends RuntimeException {

    public AuditSinkTestFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
