package com.bablsoft.accessflow.workflow.api;

/** Why an external decision hook call counted as {@link DecisionHookOutcome#FAILED} (#945). */
public enum DecisionHookFailure {

    /** The call did not finish inside the hook's timeout. */
    TIMEOUT,

    /** Connection refused, reset, TLS failure or DNS failure. */
    TRANSPORT_ERROR,

    /** A status outside 2xx; redirects are not followed, so a 3xx lands here too. */
    NON_2XX,

    /** The body was not the expected JSON, was oversized, or did not echo the request id. */
    UNPARSEABLE,

    /** The response signature was missing or did not verify. */
    SIGNATURE_MISMATCH,

    /** A decision outside the allowed set, or a missing / out-of-range approval count. */
    INVALID_DECISION,

    /** The endpoint resolved to an address the SSRF guard refuses. */
    SSRF_BLOCKED,

    /** The circuit breaker is open after repeated failures; no call was made. */
    CIRCUIT_OPEN
}
