package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * A hand-requested status other than {@code ARCHIVED} (#879) — {@code ACTIVE} is owned by the
 * promotion service and never set through the update endpoint. Mapped to HTTP 409.
 */
public final class SchemaChangeSetStatusTransitionException extends SchemaChangeException {

    private final UUID changeSetId;
    private final SchemaChangeSetStatus currentStatus;
    private final SchemaChangeSetStatus requestedStatus;

    public SchemaChangeSetStatusTransitionException(UUID changeSetId, SchemaChangeSetStatus currentStatus,
                                                    SchemaChangeSetStatus requestedStatus) {
        super("Schema change set " + changeSetId + " cannot move from " + currentStatus + " to " + requestedStatus);
        this.changeSetId = changeSetId;
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

    public UUID changeSetId() {
        return changeSetId;
    }

    public SchemaChangeSetStatus currentStatus() {
        return currentStatus;
    }

    public SchemaChangeSetStatus requestedStatus() {
        return requestedStatus;
    }
}
