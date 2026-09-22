package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * The target environment is deploy-only — it binds no datasource, so there is nothing to apply the
 * change set to (#880). Mapped to HTTP 422.
 */
public final class SchemaChangeEnvironmentNoDatasourceException extends SchemaChangeException {

    private final UUID environmentId;

    public SchemaChangeEnvironmentNoDatasourceException(UUID environmentId) {
        super("Deployment environment binds no datasource: " + environmentId);
        this.environmentId = environmentId;
    }

    public UUID environmentId() {
        return environmentId;
    }
}
