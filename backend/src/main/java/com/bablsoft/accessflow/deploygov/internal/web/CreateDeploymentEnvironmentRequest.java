package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateDeploymentEnvironmentRequest(
        @NotBlank(message = "{validation.deployment_environment.name.required}")
        @Size(max = 255, message = "{validation.deployment_environment.name.size}")
        String name,
        Integer sortOrder,
        Boolean requireReview,
        @Min(value = 1, message = "{validation.deployment_environment.required_approvals.min}")
        Integer requiredApprovals,
        UUID reviewPlanId,
        Boolean allowBreakGlass,
        @Size(max = 10, message = "{validation.deployment_environment_tags.max}")
        List<@Size(max = 32, message = "{validation.deployment_environment_tag.size}") String> tags) {

    CreateDeploymentEnvironmentCommand toCommand() {
        return new CreateDeploymentEnvironmentCommand(name, sortOrder, requireReview,
                requiredApprovals, reviewPlanId, allowBreakGlass, tags);
    }
}
