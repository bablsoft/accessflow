package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view of a deployment environment (a pipeline's promotion stage). {@code sortOrder} is
 * unique within the pipeline; {@code datasourceId} is the datasource its schema changes land on,
 * or null for a deploy-only environment (#877).
 */
public record DeploymentEnvironmentView(
        UUID id,
        UUID pipelineId,
        String name,
        int sortOrder,
        boolean requireReview,
        Integer requiredApprovals,
        UUID reviewPlanId,
        boolean allowBreakGlass,
        Instant createdAt,
        List<String> tags,
        UUID datasourceId) {
}
