package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.CreateDeploymentRoutingPolicyCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateDeploymentRoutingPolicyRequest(
        UUID pipelineId,

        @NotBlank(message = "{validation.deployment_routing_policy.name.required}")
        @Size(min = 1, max = 255, message = "{validation.deployment_routing_policy.name.size}")
        String name,

        @Valid DeploymentRoutingConditionsRequest conditions,

        @NotNull(message = "{validation.deployment_routing_policy.action.required}")
        DeploymentRoutingAction action,

        @Min(value = 1, message = "{validation.deployment_routing_policy.required_approvals.min}")
        Integer requiredApprovals,

        Integer priority,
        Boolean enabled) {

    CreateDeploymentRoutingPolicyCommand toCommand(UUID organizationId) {
        return new CreateDeploymentRoutingPolicyCommand(organizationId, pipelineId, name,
                conditions == null ? DeploymentRoutingConditions.NONE : conditions.toConditions(),
                action, requiredApprovals, priority == null ? 100 : priority,
                enabled == null || enabled);
    }
}
