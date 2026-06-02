package com.bablsoft.accessflow.workflow.api;

/**
 * The decision a matched {@code routing_policy} imposes on a query, evaluated after AI analysis and
 * before reviewer fanout.
 *
 * <ul>
 *   <li>{@link #AUTO_APPROVE} — short-circuit to {@code APPROVED} (still audited; self-approval and
 *       state-machine guards still apply).</li>
 *   <li>{@link #AUTO_REJECT} — short-circuit to {@code REJECTED}.</li>
 *   <li>{@link #REQUIRE_APPROVALS} — force human review with an <em>absolute</em> minimum number of
 *       approvals (overrides the review plan's {@code min_approvals_required}).</li>
 *   <li>{@link #ESCALATE} — force human review and add a <em>relative</em> bump on top of the review
 *       plan's requirement (effective minimum = {@code plan.minApprovalsRequired + N}).</li>
 * </ul>
 */
public enum RoutingAction {
    AUTO_APPROVE,
    AUTO_REJECT,
    REQUIRE_APPROVALS,
    ESCALATE
}
