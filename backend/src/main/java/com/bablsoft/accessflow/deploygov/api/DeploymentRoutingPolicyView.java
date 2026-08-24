package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/** A deployment routing policy. {@code pipelineId} null = the policy applies to every pipeline. */
public record DeploymentRoutingPolicyView(
        UUID id,
        UUID organizationId,
        UUID pipelineId,
        String name,
        DeploymentRoutingConditions conditions,
        DeploymentRoutingAction action,
        Integer requiredApprovals,
        int priority,
        boolean enabled,
        Instant createdAt) {
}
