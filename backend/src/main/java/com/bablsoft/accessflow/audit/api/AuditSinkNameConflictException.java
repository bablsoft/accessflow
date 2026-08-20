package com.bablsoft.accessflow.audit.api;

/** Another sink in the same organization already uses the requested name (HTTP 409). */
public class AuditSinkNameConflictException extends RuntimeException {

    public AuditSinkNameConflictException(String name) {
        super("Audit sink name already exists: " + name);
    }
}
