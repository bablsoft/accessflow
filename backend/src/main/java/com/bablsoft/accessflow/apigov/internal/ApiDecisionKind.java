package com.bablsoft.accessflow.apigov.internal;

/**
 * Which branch of the API-call decision chain produced a {@link ApiDecision} (issue AF-967). The
 * evaluator names the branch; {@code ApiReviewStateMachine} decides what to persist and publish for
 * it, so the two concerns stay separable and only one of them has side effects.
 */
enum ApiDecisionKind {

    /** A routing policy matched with {@code AUTO_APPROVE}. */
    ROUTING_AUTO_APPROVE,

    /** A routing policy matched with {@code AUTO_REJECT}. */
    ROUTING_AUTO_REJECT,

    /** A routing policy matched with {@code REQUIRE_APPROVALS}, replacing the resolved count. */
    ROUTING_REQUIRE_APPROVALS,

    /** A routing policy matched with {@code ESCALATE}, adding to the resolved count. */
    ROUTING_ESCALATE,

    /** No policy matched and neither the connector nor its plan requires human review. */
    CONNECTOR_APPROVED,

    /** No policy matched and the connector's flags or its review plan require human review. */
    CONNECTOR_PENDING_REVIEW,

    /** AI analysis failed, so the call goes to human review without consulting anything else. */
    AI_FAILED_PENDING_REVIEW
}
