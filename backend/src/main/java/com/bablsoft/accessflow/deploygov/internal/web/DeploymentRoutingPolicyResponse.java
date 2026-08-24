package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyView;

import java.time.Instant;
import java.util.UUID;

public record DeploymentRoutingPolicyResponse(
        UUID id,
        UUID pipelineId,
        String name,
        DeploymentRoutingConditionsRequest conditions,
        DeploymentRoutingAction action,
        Integer requiredApprovals,
        int priority,
        boolean enabled,
        Instant createdAt) {

    static DeploymentRoutingPolicyResponse from(DeploymentRoutingPolicyView view) {
        return new DeploymentRoutingPolicyResponse(view.id(), view.pipelineId(), view.name(),
                DeploymentRoutingConditionsRequest.from(view.conditions()), view.action(),
                view.requiredApprovals(), view.priority(), view.enabled(), view.createdAt());
    }
}
