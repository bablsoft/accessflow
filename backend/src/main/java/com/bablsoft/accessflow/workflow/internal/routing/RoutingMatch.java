package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.RoutingAction;

import java.util.UUID;

/**
 * The first {@code routing_policy} that matched a query, with the raw fields needed to apply its
 * effect. {@code requiredApprovals} is the policy's stored parameter — an absolute count for
 * {@code REQUIRE_APPROVALS}, a delta for {@code ESCALATE}, {@code null} for the auto-* actions; the
 * state machine resolves it against the review plan into an effective absolute count.
 */
public record RoutingMatch(UUID policyId, String policyName, RoutingAction action,
                           Integer requiredApprovals, String reason) {
}
