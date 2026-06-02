package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Command to create a routing policy. {@code condition} is the typed tree (the web layer converts
 * the inbound JSON to it). {@code datasourceId} is {@code null} for an org-wide policy.
 */
public record CreateRoutingPolicyCommand(
        UUID organizationId,
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
