package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * The promotion target is not an environment of the change set's pipeline — including an
 * environment of another organization, which reads identically (#880). Mapped to HTTP 404.
 */
public final class SchemaChangeEnvironmentNotFoundException extends SchemaChangeException {

    private final UUID environmentId;

    public SchemaChangeEnvironmentNotFoundException(UUID environmentId) {
        super("Deployment environment not found on the change set's pipeline: " + environmentId);
        this.environmentId = environmentId;
    }

    public UUID environmentId() {
        return environmentId;
    }
}
