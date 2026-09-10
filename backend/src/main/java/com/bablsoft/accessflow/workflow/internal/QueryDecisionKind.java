package com.bablsoft.accessflow.workflow.internal;

/**
 * Which rule decided a query, and therefore which side effects the state machine must apply
 * (issue AF-859). Kept separate from {@code QueryStatus} because two kinds can produce the same
 * status through entirely different persistence: a routing {@code REQUIRE_APPROVALS} and a plan
 * fall-through both land in {@code PENDING_REVIEW}, but only the first writes a
 * {@code routing_decision} row.
 */
enum QueryDecisionKind {

    ROUTING_AUTO_APPROVE,
    ROUTING_AUTO_REJECT,
    ROUTING_REQUIRE_APPROVALS,
    ROUTING_ESCALATE,

    /** Grant-covered auto-approval (#582). */
    GRANT_FAST_PATH,

    /** The datasource's review plan approved without human review. */
    PLAN_APPROVED,

    /** The datasource's review plan requires human review. */
    PLAN_PENDING_REVIEW,

    /** AI analysis failed; the query goes to human review unconditionally. */
    AI_FAILED_PENDING_REVIEW
}
