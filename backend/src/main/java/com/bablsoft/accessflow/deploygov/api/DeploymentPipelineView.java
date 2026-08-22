package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/** Read view of a deployment pipeline. */
public record DeploymentPipelineView(
        UUID id,
        UUID organizationId,
        String name,
        PipelineProvider provider,
        String repositoryUrl,
        String projectRef,
        UUID reviewPlanId,
        boolean aiAnalysisEnabled,
        UUID aiConfigId,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
