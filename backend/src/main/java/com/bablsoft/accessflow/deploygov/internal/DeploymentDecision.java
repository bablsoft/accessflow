package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.internal.routing.DeploymentRoutingPolicyEngine;

/**
 * What should happen to a deployment leaving {@code PENDING_AI}, and why (issue AF-967).
 *
 * @param effectiveApprovals the approval count to record when {@code nextStatus} is
 *                           {@code PENDING_REVIEW}; {@code null} on the terminal branches
 * @param trace              the ordered stages this evaluation ran, for the decision explainer
 */
record DeploymentDecision(DeploymentDecisionKind kind, QueryStatus nextStatus,
                          DeploymentRoutingPolicyEngine.RoutingMatch routingMatch,
                          Integer effectiveApprovals, DecisionTrace trace) {
}
