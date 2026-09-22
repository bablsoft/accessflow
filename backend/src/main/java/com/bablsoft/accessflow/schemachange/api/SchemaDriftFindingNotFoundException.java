package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** A drift finding does not exist in the caller's organization. Mapped to HTTP 404. */
public final class SchemaDriftFindingNotFoundException extends SchemaChangeException {

    private final UUID findingId;

    public SchemaDriftFindingNotFoundException(UUID findingId) {
        super("Schema drift finding not found: " + findingId);
        this.findingId = findingId;
    }

    public UUID findingId() {
        return findingId;
    }
}
