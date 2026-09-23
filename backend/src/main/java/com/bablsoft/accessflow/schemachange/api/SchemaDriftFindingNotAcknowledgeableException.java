package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * A resolved drift finding can no longer be acknowledged — a later scan already stopped observing
 * it, so there is nothing left to accept. Mapped to HTTP 409. Acknowledging an already-acknowledged
 * finding is idempotent and does not throw.
 */
public final class SchemaDriftFindingNotAcknowledgeableException extends SchemaChangeException {

    private final UUID findingId;
    private final SchemaDriftFindingStatus currentStatus;

    public SchemaDriftFindingNotAcknowledgeableException(UUID findingId,
                                                         SchemaDriftFindingStatus currentStatus) {
        super("Schema drift finding " + findingId + " cannot be acknowledged in status " + currentStatus);
        this.findingId = findingId;
        this.currentStatus = currentStatus;
    }

    public UUID findingId() {
        return findingId;
    }

    public SchemaDriftFindingStatus currentStatus() {
        return currentStatus;
    }
}
