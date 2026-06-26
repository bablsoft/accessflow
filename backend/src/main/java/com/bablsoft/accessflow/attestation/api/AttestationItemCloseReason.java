package com.bablsoft.accessflow.attestation.api;

/**
 * Why an item reached its terminal decision: an explicit reviewer action ({@link #REVIEWER}) or the
 * end-of-campaign default applied to a still-PENDING item ({@link #AUTO_DEFAULT_KEEP} /
 * {@link #AUTO_DEFAULT_REVOKE}).
 */
public enum AttestationItemCloseReason {
    REVIEWER,
    AUTO_DEFAULT_KEEP,
    AUTO_DEFAULT_REVOKE
}
