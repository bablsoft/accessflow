package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * A drift scan of this environment is already running, on this replica or another. Mapped to HTTP
 * 409. The check is cluster-wide, so this means "running somewhere", not "running on the node you
 * happened to reach".
 */
public final class SchemaDriftScanInProgressException extends SchemaChangeException {

    private final UUID environmentId;

    public SchemaDriftScanInProgressException(UUID environmentId) {
        super("A schema drift scan is already running for environment: " + environmentId);
        this.environmentId = environmentId;
    }

    public UUID environmentId() {
        return environmentId;
    }
}
