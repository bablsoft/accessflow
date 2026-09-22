package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * The promoting user holds no active {@code can_ddl} grant on the target datasource (#880).
 * Required for everyone, including organization admins. Mapped to HTTP 403.
 */
public final class SchemaChangePromotionDdlForbiddenException extends SchemaChangeException {

    private final UUID datasourceId;

    public SchemaChangePromotionDdlForbiddenException(UUID datasourceId) {
        super("No DDL permission on datasource: " + datasourceId);
        this.datasourceId = datasourceId;
    }

    public UUID datasourceId() {
        return datasourceId;
    }
}
