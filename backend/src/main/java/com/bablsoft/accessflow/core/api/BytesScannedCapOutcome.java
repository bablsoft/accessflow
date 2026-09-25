package com.bablsoft.accessflow.core.api;

/** How a query's bytes estimate compared against the cap that applied to it (#941). */
public enum BytesScannedCapOutcome {
    /** An estimate existed and was at or below the cap. */
    WITHIN,
    /** The estimate was above the cap: the query is refused. */
    EXCEEDED,
    /** No estimate, and the datasource says {@link BytesCapMissingEstimateAction#REQUIRE_REVIEW}. */
    NO_ESTIMATE_REVIEW,
    /** No estimate, and the datasource says {@link BytesCapMissingEstimateAction#REJECT}. */
    NO_ESTIMATE_REJECTED;

    /** Whether this outcome refuses the query. */
    public boolean rejects() {
        return this == EXCEEDED || this == NO_ESTIMATE_REJECTED;
    }
}
