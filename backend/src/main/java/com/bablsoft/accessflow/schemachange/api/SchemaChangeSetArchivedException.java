package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * Statement edits (#879) or a promotion (#880) of an {@code ARCHIVED} change set. Mapped to HTTP 409.
 */
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
