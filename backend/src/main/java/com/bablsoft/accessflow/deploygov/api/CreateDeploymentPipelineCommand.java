package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Create a deployment pipeline. {@code aiAnalysisEnabled} defaults to {@code true} when null;
 * {@code reviewPlanId} must belong to the organization; {@code aiConfigId} is stored as-is.
 */
public record CreateDeploymentPipelineCommand(
        UUID organizationId,
        String name,
        PipelineProvider provider,
        String repositoryUrl,
        String projectRef,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId) {
}
