package com.bablsoft.accessflow.access.api;

/**
 * What the usage evidence suggests should happen to a standing grant (#625).
 *
 * <p><strong>Advisory only, structurally.</strong> Nothing in the product acts on this value — it is
 * read by the over-provisioned report and by attestation reviewers, and never by routing, grant
 * coverage, break-glass, or any other decision path.
 */
public enum GrantUsageRecommendation {

    /** Too new to judge — the grant has not been observed for long enough to call it unused. */
    INSUFFICIENT_DATA,

    /** Observed for long enough and never exercised. */
    NEVER_USED,

    /** Used at some point, but not within the staleness threshold. */
    STALE,

    /** Actively used, but exercising only a small fraction of the tables/operations it grants. */
    OVER_SCOPED,

    /** Used recently and broadly enough that the grant looks justified. */
    ACTIVE
}
