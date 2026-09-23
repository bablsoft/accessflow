package com.bablsoft.accessflow.schemachange.api;

import java.util.List;
import java.util.UUID;

/** One change set's promotion ladder (#883), rungs in {@code sortOrder}. */
public record SchemaChangeLadderView(UUID changeSetId, UUID pipelineId, List<SchemaChangeLadderRungView> rungs) {

    public SchemaChangeLadderView {
        rungs = rungs == null ? List.of() : List.copyOf(rungs);
    }
}
