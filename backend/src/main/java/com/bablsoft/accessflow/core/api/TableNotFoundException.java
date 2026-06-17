package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Raised by the sample-data path (issue AF-443) when the requested table/collection is not present
 * in the datasource's introspected schema, or is outside the caller's read allow-list. Mapped to
 * HTTP 404 so existence is not leaked across the permission boundary.
 */
public final class TableNotFoundException extends DatasourceAdminException {

    public TableNotFoundException(UUID datasourceId, String table) {
        super("Table not found in datasource " + datasourceId + ": " + table);
    }
}
