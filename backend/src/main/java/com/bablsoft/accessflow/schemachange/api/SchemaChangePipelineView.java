package com.bablsoft.accessflow.schemachange.api;

import java.util.List;
import java.util.UUID;

/** A deployment pipeline as schema change governance sees it (#883): a name and a ladder. */
public record SchemaChangePipelineView(UUID id, String name, boolean active,
                                       List<SchemaChangePipelineEnvironmentView> environments) {

    public SchemaChangePipelineView {
        environments = environments == null ? List.of() : List.copyOf(environments);
    }
}
