package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentRoutingPolicyCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Null fields stay unchanged; {@code clearPipeline} widens the policy to the whole organization. */
public record UpdateDeploymentRoutingPolicyRequest(
        UUID pipelineId,
        Boolean clearPipeline,

        @Size(min = 1, max = 255, message = "{validation.deployment_routing_policy.name.size}")
        String name,

        @Valid DeploymentRoutingConditionsRequest conditions,

        DeploymentRoutingAction action,

        @Min(value = 1, message = "{validation.deployment_routing_policy.required_approvals.min}")
        Integer requiredApprovals,

        Integer priority,
        Boolean enabled) {

    UpdateDeploymentRoutingPolicyCommand toCommand() {
        return new UpdateDeploymentRoutingPolicyCommand(pipelineId, clearPipeline, name,
                conditions == null ? null : conditions.toConditions(), action, requiredApprovals,
                priority, enabled);
    }
}
