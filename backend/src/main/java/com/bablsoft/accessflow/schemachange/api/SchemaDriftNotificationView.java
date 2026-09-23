package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** The drift-scan target a {@code SCHEMA_DRIFT_DETECTED} notification names (#882). */
public record SchemaDriftNotificationView(
        UUID organizationId,
        UUID pipelineId,
        String pipelineName,
        UUID environmentId,
        String environmentName) {
}
