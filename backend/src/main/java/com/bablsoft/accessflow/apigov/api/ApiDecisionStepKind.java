package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.DecisionStepKind;
import com.bablsoft.accessflow.core.api.StepOutcome;

/**
 * The ordered stages a governed API call passes through on its way to a status (issue AF-967), in the
 * order they are evaluated.
 *
 * <p>A trace always carries every kind exactly once — a stage that did not apply is reported with
 * {@link StepOutcome#SKIP} rather than omitted, so a client can render a stable checklist and a
 * missing stage is always a bug rather than a normal outcome.
 *
 * <p>Only {@link #ROUTING_POLICIES} and {@link #REVIEW_REQUIREMENT} are decided on the live
 * asynchronous path. The rest happen elsewhere in production — the first four in the synchronous
 * submission gate, {@link #RESPONSE_MASKING} at execution time, {@link #ELIGIBLE_REVIEWERS} in
 * notification fan-out, and {@link #BREAK_GLASS} in a separate submission mode — and are reconstructed
 * by the simulator so one trace covers the whole journey.
 *
 * <p>There is deliberately <strong>no connector-health stage</strong>: the only reachability probe in
 * the product is the admin-triggered connection test, and the submit path gates on {@code active} and
 * nothing else. A health stage would report something enforcement never checks.
 */
public enum ApiDecisionStepKind implements DecisionStepKind {

    /** The connector exists in the organization and is active. */
    CONNECTOR_GATES,

    /** Read or write, and which of the three rules classified it. */
    CALL_CLASSIFICATION,

    /** The operation is present in the ingested schema catalog. */
    SCHEMA_VALIDATION,

    /** The effective connector permission: capability, operation allow-list, admin short-circuit. */
    OPERATION_PERMISSION,

    /** Every enabled routing policy in ascending priority order, matched and unmatched. */
    ROUTING_POLICIES,

    /** The connector's require-review flags folded with its review plan. */
    REVIEW_REQUIREMENT,

    /** Who could act on the request, with the submitter excluded under the self-approval ban. */
    ELIGIBLE_REVIEWERS,

    /** The masking rules that would rewrite the response. Reported per rule, never per field. */
    RESPONSE_MASKING,

    /** Whether the submitter could bypass everything above. Informational. */
    BREAK_GLASS
}
