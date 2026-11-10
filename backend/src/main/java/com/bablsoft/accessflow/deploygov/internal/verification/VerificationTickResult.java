package com.bablsoft.accessflow.deploygov.internal.verification;

/** What one verification tick did to one deployment request (#1025). */
public enum VerificationTickResult {
    /** Samples recorded; the window is still open. */
    SAMPLED,
    /** A check reached its failure threshold; the request flipped to FAILED. */
    BREACHED,
    /** The window elapsed with no breach. */
    PASSED,
    /** Nothing to do: not running, or the next sample is not yet due. */
    SKIPPED
}
