package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * A drift finding or drift configuration was changed by someone else while this request was writing
 * it — a scan re-observing the finding being acknowledged, or two first-time configuration writes for
 * the same pipeline. Mapped to HTTP 409; the request is safe to retry.
 */
public final class SchemaDriftConcurrentUpdateException extends SchemaChangeException {

    private final UUID resourceId;

    public SchemaDriftConcurrentUpdateException(UUID resourceId) {
        super("Schema drift resource was changed concurrently: " + resourceId);
        this.resourceId = resourceId;
    }

    public UUID resourceId() {
        return resourceId;
    }
}
