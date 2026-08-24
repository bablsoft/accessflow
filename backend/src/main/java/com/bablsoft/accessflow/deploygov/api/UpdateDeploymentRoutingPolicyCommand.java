package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Update a deployment routing policy. A null field leaves the current value unchanged;
 * {@code clearPipeline} widens a pipeline-scoped policy to the whole organization and wins over any
 * {@code pipelineId} sent in the same request.
 */
public record UpdateDeploymentRoutingPolicyCommand(
        UUID pipelineId,
        Boolean clearPipeline,
        String name,
        DeploymentRoutingConditions conditions,
        DeploymentRoutingAction action,
        Integer requiredApprovals,
        Integer priority,
        Boolean enabled) {
}
