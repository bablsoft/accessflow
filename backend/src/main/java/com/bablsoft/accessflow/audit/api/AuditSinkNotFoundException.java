package com.bablsoft.accessflow.audit.api;

import java.util.UUID;

public class AuditSinkNotFoundException extends RuntimeException {

    private final UUID sinkId;

    public AuditSinkNotFoundException(UUID sinkId) {
        super("Audit sink not found: " + sinkId);
        this.sinkId = sinkId;
    }

    public UUID sinkId() {
        return sinkId;
    }
}
