package com.bablsoft.accessflow.workflow.api;

/**
 * Which of the three AI-analysis outcomes a query decision is being made under (issue AF-859).
 *
 * <p>The three live paths arrive as three distinct events, so there was no shared enum to name the
 * branch. Making it a parameter is what lets the access simulator drive the identical evaluator the
 * listeners drive, instead of reimplementing the branch selection and drifting from it.
 */
public enum AiOutcome {

    /** Analysis returned a verdict; the risk level and score are the signal. */
    COMPLETED,

    /**
     * Analysis was skipped because the datasource has {@code ai_analysis_enabled = false}. There is
     * no risk signal, so risk-based routing conditions evaluate to {@code false} and the review
     * plan's {@code auto_approve_reads} fast path cannot fire.
     */
    SKIPPED,

    /**
     * Analysis errored. Production sends the query straight to {@code PENDING_REVIEW} so a human can
     * inspect it: routing does not run (no risk signal, and a failed analysis is not a positive
     * auto-decision signal), and neither does the grant fast path or the review plan.
     */
    FAILED
}
