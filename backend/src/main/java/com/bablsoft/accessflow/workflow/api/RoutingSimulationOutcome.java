package com.bablsoft.accessflow.workflow.api;

/**
 * What routing would decide for one query (issue AF-630) — the four {@code routing_action} effects
 * plus {@link #NO_MATCH}.
 *
 * <p>{@code NO_MATCH} is not "nothing happens": it means no policy matched, so the query falls
 * through to the grant-covered auto-approval fast path (#582) and then the datasource's review
 * plan. The simulator models the policy layer only, and says so.
 */
public enum RoutingSimulationOutcome {
    AUTO_APPROVE,
    AUTO_REJECT,
    REQUIRE_APPROVALS,
    ESCALATE,
    NO_MATCH
}
