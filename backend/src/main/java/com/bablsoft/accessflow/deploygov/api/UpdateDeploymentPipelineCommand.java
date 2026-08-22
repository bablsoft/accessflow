package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Update a deployment pipeline. Null fields are left unchanged. {@code clearReviewPlan} /
 * {@code clearAiConfig} unassign the nullable references and win over an id supplied in the same
 * command.
 */
public record UpdateDeploymentPipelineCommand(
        String name,
        PipelineProvider provider,
        String repositoryUrl,
        String projectRef,
        UUID reviewPlanId,
        Boolean clearReviewPlan,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean clearAiConfig,
        Boolean active) {
}
