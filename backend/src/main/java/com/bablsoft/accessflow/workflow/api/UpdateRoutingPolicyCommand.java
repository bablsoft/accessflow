package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Command to fully update a routing policy (PUT semantics — every field is replaced).
 * {@code datasourceId} is {@code null} for an org-wide policy.
 */
public record UpdateRoutingPolicyCommand(
        UUID datasourceId,
        String name,
        String description,
        int priority,
        boolean enabled,
        ConditionNode condition,
        RoutingAction action,
        Integer requiredApprovals,
        String reason) {
}
