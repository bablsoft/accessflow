package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** A change set without statements cannot be promoted — nothing to send, nothing to attest (#880). Mapped to HTTP 409. */
public final class SchemaChangeSetEmptyException extends SchemaChangeException {

    private final UUID changeSetId;

    public SchemaChangeSetEmptyException(UUID changeSetId) {
        super("Schema change set has no statements: " + changeSetId);
        this.changeSetId = changeSetId;
    }

    public UUID changeSetId() {
        return changeSetId;
    }
}
