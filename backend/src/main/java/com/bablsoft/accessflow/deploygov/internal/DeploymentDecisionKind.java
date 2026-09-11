package com.bablsoft.accessflow.deploygov.internal;

/**
 * Which branch of the deployment decision chain produced a {@link DeploymentDecision} (issue AF-967).
 * The evaluator names the branch; {@code DeploymentReviewStateMachine} decides what to persist, audit
 * and publish for it, so the two concerns stay separable and only one of them has side effects.
 */
enum DeploymentDecisionKind {

    /** A routing policy matched with {@code AUTO_APPROVE}. */
    ROUTING_AUTO_APPROVE,

    /** A routing policy matched with {@code AUTO_REJECT}. */
    ROUTING_AUTO_REJECT,

    /** A routing policy matched with {@code REQUIRE_APPROVALS}, replacing the resolved count. */
    ROUTING_REQUIRE_APPROVALS,

    /** A routing policy matched with {@code ESCALATE}, adding to the resolved count. */
    ROUTING_ESCALATE,

    /** No policy matched and neither the environment nor its plan requires human review. */
    ENVIRONMENT_APPROVED,

    /** No policy matched and the environment or its review plan requires human review. */
    ENVIRONMENT_PENDING_REVIEW,

    /** AI analysis failed, so the deployment goes to human review without consulting anything else. */
    AI_FAILED_PENDING_REVIEW
}
