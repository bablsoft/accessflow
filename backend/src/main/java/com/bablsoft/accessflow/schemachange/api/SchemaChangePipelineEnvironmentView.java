package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** One rung of a pipeline; {@code datasourceId} is null for a deploy-only environment. */
public record SchemaChangePipelineEnvironmentView(UUID id, String name, int sortOrder, UUID datasourceId) {
}
