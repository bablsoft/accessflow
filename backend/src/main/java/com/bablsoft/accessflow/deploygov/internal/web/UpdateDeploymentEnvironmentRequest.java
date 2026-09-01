package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentEnvironmentCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateDeploymentEnvironmentRequest(
        @Size(min = 1, max = 255, message = "{validation.deployment_environment.name.size}")
        String name,
        Integer sortOrder,
        Boolean requireReview,
        @Min(value = 1, message = "{validation.deployment_environment.required_approvals.min}")
        Integer requiredApprovals,
        Boolean clearRequiredApprovals,
        UUID reviewPlanId,
        Boolean clearReviewPlan,
        Boolean allowBreakGlass,
        @Size(max = 10, message = "{validation.deployment_environment_tags.max}")
        List<@Size(max = 32, message = "{validation.deployment_environment_tag.size}") String> tags) {

    UpdateDeploymentEnvironmentCommand toCommand() {
        return new UpdateDeploymentEnvironmentCommand(name, sortOrder, requireReview,
                requiredApprovals, clearRequiredApprovals, reviewPlanId, clearReviewPlan,
                allowBreakGlass, tags);
    }
}
