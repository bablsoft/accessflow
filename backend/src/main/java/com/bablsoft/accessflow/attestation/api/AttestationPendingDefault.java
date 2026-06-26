package com.bablsoft.accessflow.attestation.api;

/**
 * What happens to items left {@link AttestationItemDecision#PENDING} when a campaign closes:
 * {@link #KEEP} certifies them (access retained) or {@link #REVOKE} revokes the underlying grant.
 */
public enum AttestationPendingDefault {
    KEEP,
    REVOKE
}
