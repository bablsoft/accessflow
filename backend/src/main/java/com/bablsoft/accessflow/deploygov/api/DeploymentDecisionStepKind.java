package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.DecisionStepKind;
import com.bablsoft.accessflow.core.api.StepOutcome;

/**
 * The ordered stages a deployment request passes through, from the CI trigger to the release gate
 * (issue AF-967), in the order they are evaluated.
 *
 * <p>A trace always carries every kind exactly once — a stage that did not apply is reported with
 * {@link StepOutcome#SKIP} rather than omitted, so a client can render a stable checklist and a
 * missing stage is always a bug rather than a normal outcome.
 *
 * <p>Only {@link #ROUTING_POLICIES} and {@link #ENVIRONMENT_POLICY} are decided on the live
 * asynchronous path. The rest happen elsewhere — the first three in the synchronous trigger,
 * {@link #ELIGIBLE_REVIEWERS} in notification fan-out, {@link #SCHEDULED_RELEASE} and
 * {@link #GATE_RELEASABILITY} when the pipeline polls the gate, and {@link #BREAK_GLASS} in a
 * separate submission mode — and are reconstructed by the simulator so one trace covers the whole
 * journey. That is the point on this kind especially: a release is blocked by the <em>gate</em>, not
 * by the decision, and the two are several hours and several screens apart.
 */
public enum DeploymentDecisionStepKind implements DecisionStepKind {

    /** The pipeline is active in the organization and the environment belongs to it. */
    PIPELINE_GATES,

    /** The most-permissive union of the direct trigger grant and every unexpired group grant. */
    TRIGGER_PERMISSION,

    /** The freeze window in effect: {@code REJECT} refuses at trigger, {@code HOLD} withholds release. */
    FREEZE_WINDOW,

    /** Every enabled routing policy in ascending priority order, matched and unmatched. */
    ROUTING_POLICIES,

    /** The environment's require-review flag, approval override and resolved review plan. */
    ENVIRONMENT_POLICY,

    /** Who could act on the request, with the submitter excluded under the self-approval ban. */
    ELIGIBLE_REVIEWERS,

    /** Whether a deferred release moment has passed. */
    SCHEDULED_RELEASE,

    /** The gate's own verdict — the same pure function the CI job blocks on. */
    GATE_RELEASABILITY,

    /** Whether the submitter could bypass everything above. Informational. */
    BREAK_GLASS
}
