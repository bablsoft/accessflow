package com.bablsoft.accessflow.core.api;

/**
 * Derived lifecycle state of a review delegation (#622). Never stored — computed from
 * {@code revoked_at} and the window against the current instant, so a delegation moves from
 * SCHEDULED to ACTIVE to EXPIRED with no job and no write.
 */
public enum ReviewDelegationStatus {
    /** Created, but its window has not opened yet. */
    SCHEDULED,
    /** Inside its window and not revoked — currently confers eligibility. */
    ACTIVE,
    /** Its window has closed. */
    EXPIRED,
    /** Revoked by the delegator before its window closed. */
    REVOKED
}
