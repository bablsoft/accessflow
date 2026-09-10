package com.bablsoft.accessflow.core.api;

/**
 * Which of the three AI-analysis outcomes a governed request's decision is being made under
 * (issues AF-859, AF-967).
 *
 * <p>The three live paths arrive as three distinct events, so there was no shared enum to name the
 * branch. Making it a parameter is what lets a simulator drive the identical evaluator the listeners
 * drive, instead of reimplementing the branch selection and drifting from it. All three governed
 * request kinds — queries, API calls and deployments — publish the same three analysis events, so
 * the enum is shared.
 */
public enum AiOutcome {

    /** Analysis returned a verdict; the risk level and score are the signal. */
    COMPLETED,

    /**
     * Analysis was skipped because the governing resource has {@code ai_analysis_enabled = false} —
     * the datasource, the API connector or the deployment pipeline. There is no risk signal, so
     * risk-based routing conditions evaluate to {@code false} and the query review plan's
     * {@code auto_approve_reads} fast path cannot fire.
     */
    SKIPPED,

    /**
     * Analysis errored. Production sends the request straight to {@code PENDING_REVIEW} so a human
     * can inspect it: routing does not run on any of the three kinds (no risk signal, and a failed
     * analysis is not a positive auto-decision signal), and neither does the query grant fast path,
     * the review plan, the connector's require-review flags or the environment's policy.
     */
    FAILED
}
