package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DeploymentEnvironmentResponse(
        UUID id,
        UUID pipelineId,
        String name,
        int sortOrder,
        boolean requireReview,
        Integer requiredApprovals,
        UUID reviewPlanId,
        boolean allowBreakGlass,
        Instant createdAt,
        List<String> tags) {

    static DeploymentEnvironmentResponse from(DeploymentEnvironmentView view) {
        return new DeploymentEnvironmentResponse(view.id(), view.pipelineId(), view.name(),
                view.sortOrder(), view.requireReview(), view.requiredApprovals(),
                view.reviewPlanId(), view.allowBreakGlass(), view.createdAt(), view.tags());
    }
}
