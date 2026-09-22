package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** Optional filters for a change-set listing (#878, epic #870); a null field is "any". */
public record SchemaChangeSetListFilter(UUID pipelineId, SchemaChangeSetStatus status) {

    public static SchemaChangeSetListFilter none() {
        return new SchemaChangeSetListFilter(null, null);
    }
}
