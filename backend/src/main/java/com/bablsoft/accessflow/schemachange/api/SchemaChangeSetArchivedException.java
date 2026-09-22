package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** Statement edits on an {@code ARCHIVED} change set (#879). Mapped to HTTP 409. */
public final class SchemaChangeSetArchivedException extends SchemaChangeException {

    private final UUID changeSetId;

    public SchemaChangeSetArchivedException(UUID changeSetId) {
        super("Schema change set is archived: " + changeSetId);
        this.changeSetId = changeSetId;
    }

    public UUID changeSetId() {
        return changeSetId;
    }
}
