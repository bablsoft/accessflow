package com.bablsoft.accessflow.workflow.api;

/**
 * The ordered stages a query passes through on its way to a status (issue AF-859), in the order
 * they are evaluated.
 *
 * <p>A trace always carries every kind exactly once — a stage that did not apply is reported with
 * {@link StepOutcome#SKIP} rather than omitted, so a client can render a stable checklist and a
 * missing stage is always a bug rather than a normal outcome.
 *
 * <p>Only {@link #ROUTING_POLICIES}, {@link #GRANT_FAST_PATH} and {@link #REVIEW_PLAN} are decided
 * on the live asynchronous path. The rest are evaluated elsewhere in production — the first four by
 * the synchronous submission gate, the last four at execution time — and are reconstructed by the
 * access simulator so one trace covers the whole journey.
 */
public enum DecisionStepKind {

    /** Datasource exists, is visible to the submitter, is active, and its AI-analysis setting. */
    DATASOURCE_GATES,

    /** The organization's query quota. */
    QUOTA,

    /** JSqlParser validation: resolved query type, referenced tables, transactional envelope. */
    SQL_PARSE,

    /** The most-permissive union of the direct grant and every unexpired group grant. */
    EFFECTIVE_PERMISSION,

    /** Every enabled routing policy in ascending priority order, matched and unmatched. */
    ROUTING_POLICIES,

    /** Grant-covered auto-approval (#582). */
    GRANT_FAST_PATH,

    /** The datasource's review plan and its resolved approval count. */
    REVIEW_PLAN,

    /** Who could act on the request, with the submitter excluded under the self-approval ban. */
    ELIGIBLE_REVIEWERS,

    /** The row-security predicates that would be injected, classified offline. */
    ROW_SECURITY,

    /** The masking policies resolving for the referenced tables. */
    MASKING,

    /** Whether the submitter could bypass everything above. Informational. */
    BREAK_GLASS
}
