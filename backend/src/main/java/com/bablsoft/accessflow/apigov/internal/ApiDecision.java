package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.apigov.internal.routing.ApiRoutingPolicyEngine;

/**
 * What should happen to an API call leaving {@code PENDING_AI}, and why (issue AF-967).
 *
 * @param effectiveApprovals the approval count to record when {@code nextStatus} is
 *                           {@code PENDING_REVIEW}; {@code null} on the terminal branches
 * @param trace              the ordered stages this evaluation ran, for the decision explainer
 */
record ApiDecision(ApiDecisionKind kind, QueryStatus nextStatus,
                   ApiRoutingPolicyEngine.RoutingMatch routingMatch, Integer effectiveApprovals,
                   DecisionTrace trace) {
}
