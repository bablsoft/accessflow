package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Create a deployment routing policy. {@code requiredApprovals} is required for
 * {@code REQUIRE_APPROVALS} (the absolute approver count) and {@code ESCALATE} (a delta added to the
 * resolved count), and must be null for {@code AUTO_APPROVE} / {@code AUTO_REJECT}.
 */
public record CreateDeploymentRoutingPolicyCommand(
        UUID organizationId,
        UUID pipelineId,
        String name,
        DeploymentRoutingConditions conditions,
        DeploymentRoutingAction action,
        Integer requiredApprovals,
        int priority,
        boolean enabled) {
}
