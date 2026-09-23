package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineView;

import java.util.List;
import java.util.UUID;

public record SchemaChangePipelineResponse(UUID id, String name, boolean active, List<Environment> environments) {

    public record Environment(UUID id, String name, int sortOrder, UUID datasourceId) {
    }

    static SchemaChangePipelineResponse from(SchemaChangePipelineView view) {
        return new SchemaChangePipelineResponse(view.id(), view.name(), view.active(), view.environments().stream()
                .map(e -> new Environment(e.id(), e.name(), e.sortOrder(), e.datasourceId()))
                .toList());
    }
}
