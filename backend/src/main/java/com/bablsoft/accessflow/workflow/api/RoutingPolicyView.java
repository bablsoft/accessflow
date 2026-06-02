package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-layer DTO for a routing policy, carrying the typed {@link ConditionNode} tree (not raw
 * JSON). {@code datasourceId} is {@code null} for an org-wide policy. {@code requiredApprovals} is
 * the policy parameter — an absolute count for {@code REQUIRE_APPROVALS}, a delta for
 * {@code ESCALATE}, {@code null} for the auto-* actions.
 */
public record RoutingPolicyView(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        String name,
        String description,
        int priority,
        boolean enabled,
        ConditionNode condition,
        RoutingAction action,
        Integer requiredApprovals,
        String reason,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
