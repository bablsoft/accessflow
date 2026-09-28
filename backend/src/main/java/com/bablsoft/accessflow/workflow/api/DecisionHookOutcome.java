package com.bablsoft.accessflow.workflow.api;

/**
 * What an external decision hook concluded for one query (#945). Deliberately a subset of the
 * routing actions: there is no approve, so a compromised or misconfigured endpoint can add friction
 * but never remove it.
 */
public enum DecisionHookOutcome {

    /** No effect: the grant fast path and the review plan decide as if no hook existed. */
    ALLOW,

    /** Human review with the plan's minimum plus the requested approvals. */
    ESCALATE,

    /** Human review with at least the requested approvals — never fewer than the plan's minimum. */
    REQUIRE_APPROVALS,

    /** The query is rejected. */
    REJECT,

    /** The hook could not be trusted to have answered; the query goes to human review. */
    FAILED
}
