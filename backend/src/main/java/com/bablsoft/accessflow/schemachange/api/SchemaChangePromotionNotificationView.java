package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** What a promotion notification renders (#882). Names are null when their row is gone. */
public record SchemaChangePromotionNotificationView(
        UUID id,
        UUID organizationId,
        UUID changeSetId,
        String changeSetName,
        UUID pipelineId,
        String pipelineName,
        UUID environmentId,
        String environmentName,
        UUID datasourceId,
        UUID promotedBy,
        SchemaChangePromotionStatus status,
        String errorMessage) {
}
