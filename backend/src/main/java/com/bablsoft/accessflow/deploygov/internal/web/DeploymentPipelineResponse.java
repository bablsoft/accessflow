package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;

import java.time.Instant;
import java.util.UUID;

public record DeploymentPipelineResponse(
        UUID id,
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

    static DeploymentPipelineResponse from(DeploymentPipelineView view) {
        return new DeploymentPipelineResponse(view.id(), view.name(), view.provider(),
                view.repositoryUrl(), view.projectRef(), view.reviewPlanId(),
                view.aiAnalysisEnabled(), view.aiConfigId(), view.active(),
                view.createdAt(), view.updatedAt());
    }
}
