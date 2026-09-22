package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * A non-terminal promotion of the change set to the environment already exists — the partial
 * unique index {@code uq_schema_change_set_promotions_open}. Mapped to HTTP 409.
 */
public final class SchemaChangePromotionConflictException extends SchemaChangeException {

    private final UUID changeSetId;
    private final UUID environmentId;

    public SchemaChangePromotionConflictException(UUID changeSetId, UUID environmentId) {
        super("Schema change set " + changeSetId + " already has an open promotion to environment "
                + environmentId);
        this.changeSetId = changeSetId;
        this.environmentId = environmentId;
    }

    public UUID changeSetId() {
        return changeSetId;
    }

    public UUID environmentId() {
        return environmentId;
    }
}
