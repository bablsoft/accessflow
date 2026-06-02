package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.RoutingAction;

import java.util.UUID;

/**
 * The routing decision recorded for a query, enriched with the matched policy's name (which is
 * {@code null} when the policy has since been deleted), for the query-detail timeline.
 */
public record MatchedRoutingPolicyView(UUID policyId, String policyName, RoutingAction action,
                                       String reason) {
}
