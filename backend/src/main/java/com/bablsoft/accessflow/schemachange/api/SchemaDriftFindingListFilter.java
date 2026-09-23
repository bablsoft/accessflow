package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * Nullable filters for the drift finding worklist (#881). A null field means "no restriction"; an id
 * that matches nothing yields an empty page rather than a 404 — a filter value is not a resource.
 */
public record SchemaDriftFindingListFilter(UUID pipelineId, UUID environmentId,
                                           SchemaDriftFindingStatus status) {

    public static SchemaDriftFindingListFilter unfiltered() {
        return new SchemaDriftFindingListFilter(null, null, null);
    }
}
