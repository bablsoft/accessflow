package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * The change set has a promotion that is not {@code FAILED} / {@code CANCELLED}, so its statements
 * and its existence are frozen (#879). Mapped to HTTP 409.
 */
public final class SchemaChangeSetFrozenException extends SchemaChangeException {

    private final UUID changeSetId;

    public SchemaChangeSetFrozenException(UUID changeSetId) {
        super("Schema change set is frozen by a promotion: " + changeSetId);
        this.changeSetId = changeSetId;
    }

    public UUID changeSetId() {
        return changeSetId;
    }
}
