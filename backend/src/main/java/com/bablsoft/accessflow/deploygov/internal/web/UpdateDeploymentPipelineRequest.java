package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentPipelineCommand;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateDeploymentPipelineRequest(
        @Size(min = 3, max = 255, message = "{validation.deployment_pipeline.name.size}")
        String name,
        PipelineProvider provider,
        @Size(max = 2048, message = "{validation.deployment_pipeline.repository_url.size}")
        String repositoryUrl,
        @Size(max = 512, message = "{validation.deployment_pipeline.project_ref.size}")
        String projectRef,
        UUID reviewPlanId,
        Boolean clearReviewPlan,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean clearAiConfig,
        Boolean active) {

    UpdateDeploymentPipelineCommand toCommand() {
        return new UpdateDeploymentPipelineCommand(name, provider, repositoryUrl, projectRef,
                reviewPlanId, clearReviewPlan, aiAnalysisEnabled, aiConfigId, clearAiConfig, active);
    }
}
