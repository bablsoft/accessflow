package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** A change set does not exist in the caller's organization. Mapped to HTTP 404. */
public final class SchemaChangeSetNotFoundException extends SchemaChangeException {

    private final UUID changeSetId;

    public SchemaChangeSetNotFoundException(UUID changeSetId) {
        super("Schema change set not found: " + changeSetId);
        this.changeSetId = changeSetId;
    }

    public UUID changeSetId() {
        return changeSetId;
    }
}
