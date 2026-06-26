package com.bablsoft.accessflow.attestation.api;

/**
 * Per-item attestation decision. {@link #PENDING} until a reviewer acts (or the campaign closes and
 * applies its default); terminal once {@link #CERTIFIED} or {@link #REVOKED}.
 */
public enum AttestationItemDecision {
    PENDING,
    CERTIFIED,
    REVOKED
}
