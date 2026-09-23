package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * Nullable filters for the drift scan listing (#881). A null field means "no restriction"; an id
 * that matches nothing yields an empty page rather than a 404 — a filter value is not a resource.
 */
public record SchemaDriftScanListFilter(UUID pipelineId, UUID environmentId) {

    public static SchemaDriftScanListFilter unfiltered() {
        return new SchemaDriftScanListFilter(null, null);
    }
}
