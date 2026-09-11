package com.bablsoft.accessflow.core.api;

/**
 * The seam that lets one {@link DecisionTrace} type serve three governed request kinds (issue
 * AF-967).
 *
 * <p>Implemented by one enum per module — {@code workflow.api.QueryDecisionStepKind},
 * {@code apigov.api.ApiDecisionStepKind}, {@code deploygov.api.DeploymentDecisionStepKind} — each
 * naming the stages that kind actually has, in the order they are evaluated. Every enum satisfies
 * this interface implicitly through {@link Enum#name()}.
 *
 * <p>Deliberately <em>not</em> one widened enum. A deployment has freeze windows and a releasability
 * gate; an API call has connector gates and response masking; a query has SQL parsing and row
 * security. Folding all of those into a single enum would mean two thirds of every trace reporting
 * {@code SKIP} for stages that can never apply to it, which reads as "this did not happen" when the
 * truth is "this does not exist here".
 */
public interface DecisionStepKind {

    /** The stage's stable name, as it appears on the wire. */
    String name();
}
