package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingMatch;

import java.util.UUID;

/**
 * What the decision chain concluded for one query (issue AF-859) — everything the state machine
 * needs to persist and publish, plus the trace explaining how it got there.
 *
 * <p>Internal rather than exposed because it carries {@link RoutingMatch}, and because no other
 * module needs to act on a decision; the {@link DecisionTrace} alone is what crosses the boundary.
 *
 * @param routingMatch        the policy that decided this, or {@code null} when routing did not
 * @param effectiveApprovals  the resolved absolute approval count for the routing actions that raise
 *                            the bar, {@code null} otherwise
 * @param grantId             the covering JIT grant on {@link QueryDecisionKind#GRANT_FAST_PATH}
 * @param grantApproverEmail  who approved that grant, for the auto-approval event's provenance
 * @param context             the signals routing was evaluated against, or {@code null} on the
 *                            AI-failure path, which decides before any context is built
 */
record QueryDecision(QueryDecisionKind kind, QueryStatus nextStatus, RoutingMatch routingMatch,
                     Integer effectiveApprovals, UUID grantId, String grantApproverEmail,
                     ConditionContext context, DecisionTrace trace) {
}
