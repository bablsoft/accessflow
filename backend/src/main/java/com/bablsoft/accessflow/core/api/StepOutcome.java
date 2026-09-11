package com.bablsoft.accessflow.core.api;

/**
 * What one {@link DecisionStepKind} concluded (issues AF-859, AF-967).
 *
 * <p>The gate-shaped stages report {@link #ALLOW} / {@link #DENY}; the policy-shaped ones report
 * {@link #MATCH} / {@link #NO_MATCH}. Keeping the two vocabularies distinct rather than collapsing
 * them onto a boolean is deliberate: "no routing policy matched" is not a denial, and rendering it
 * as one is exactly the misreading the explainer exists to prevent.
 */
public enum StepOutcome {

    /** The stage is a gate and the request passed it. */
    ALLOW,

    /** The stage is a gate and the request failed it. */
    DENY,

    /** The stage is a rule set and something matched. */
    MATCH,

    /** The stage is a rule set and nothing matched — the request falls through to the next stage. */
    NO_MATCH,

    /** The stage did not apply on this path. */
    SKIP
}
