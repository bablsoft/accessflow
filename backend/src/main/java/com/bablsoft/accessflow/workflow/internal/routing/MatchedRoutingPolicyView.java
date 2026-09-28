package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingDecisionSource;

import java.util.UUID;

/**
 * The routing decision recorded for a query, enriched with the matched policy's name (which is
 * {@code null} when the policy has since been deleted), for the query-detail timeline. When the
 * external decision hook decided (#945), {@code source} is {@code DECISION_HOOK}, the policy fields
 * are null and the hook fields are set.
 */
public record MatchedRoutingPolicyView(UUID policyId, String policyName, RoutingAction action,
                                       String reason, RoutingDecisionSource source,
                                       UUID decisionHookId) {

    /** A decision a routing policy made. */
    public MatchedRoutingPolicyView(UUID policyId, String policyName, RoutingAction action,
                                    String reason) {
        this(policyId, policyName, action, reason, RoutingDecisionSource.POLICY, null);
    }
}
