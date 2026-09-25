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
 * @param sqlReviewSuppression which auto-approve paths a {@code BLOCK} SQL review finding turned
 *                            into human review (#864), or {@code null} when the finding changed
 *                            nothing — no block, {@code WARN} only, or the request was headed to
 *                            review regardless
 * @param bytesCap            the bytes-scanned cap that applied (#941), or {@code null} when none
 * @param bytesCapChangedOutcome whether the cap refused the query or turned an automatic approval
 *                            into human review — the condition for its audit row
 */
record QueryDecision(QueryDecisionKind kind, QueryStatus nextStatus, RoutingMatch routingMatch,
                     Integer effectiveApprovals, UUID grantId, String grantApproverEmail,
                     ConditionContext context, DecisionTrace trace,
                     SqlReviewSuppression sqlReviewSuppression, BytesCapCheck bytesCap,
                     boolean bytesCapChangedOutcome) {

    /** A decision no bytes-scanned cap took part in. */
    QueryDecision(QueryDecisionKind kind, QueryStatus nextStatus, RoutingMatch routingMatch,
                  Integer effectiveApprovals, UUID grantId, String grantApproverEmail,
                  ConditionContext context, DecisionTrace trace,
                  SqlReviewSuppression sqlReviewSuppression) {
        this(kind, nextStatus, routingMatch, effectiveApprovals, grantId, grantApproverEmail, context,
                trace, sqlReviewSuppression, null, false);
    }

    /** A decision no SQL review finding interfered with. */
    QueryDecision(QueryDecisionKind kind, QueryStatus nextStatus, RoutingMatch routingMatch,
                  Integer effectiveApprovals, UUID grantId, String grantApproverEmail,
                  ConditionContext context, DecisionTrace trace) {
        this(kind, nextStatus, routingMatch, effectiveApprovals, grantId, grantApproverEmail, context,
                trace, (SqlReviewSuppression) null);
    }
}
