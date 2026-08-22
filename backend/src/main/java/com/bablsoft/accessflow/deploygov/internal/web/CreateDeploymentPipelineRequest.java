package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateDeploymentPipelineRequest(
        @NotBlank(message = "{validation.deployment_pipeline.name.required}")
        @Size(min = 3, max = 255, message = "{validation.deployment_pipeline.name.size}")
        String name,
        @NotNull(message = "{validation.deployment_pipeline.provider.required}")
        PipelineProvider provider,
        @Size(max = 2048, message = "{validation.deployment_pipeline.repository_url.size}")
        String repositoryUrl,
        @Size(max = 512, message = "{validation.deployment_pipeline.project_ref.size}")
        String projectRef,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId) {

    CreateDeploymentPipelineCommand toCommand(UUID organizationId) {
        return new CreateDeploymentPipelineCommand(organizationId, name, provider, repositoryUrl,
                projectRef, reviewPlanId, aiAnalysisEnabled, aiConfigId);
    }
}
