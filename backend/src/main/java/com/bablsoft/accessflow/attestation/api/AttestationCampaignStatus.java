package com.bablsoft.accessflow.attestation.api;

/**
 * Lifecycle of an access-recertification campaign (AF-384).
 *
 * <pre>
 * SCHEDULED → OPEN → CLOSED
 *           ↘ CANCELLED   (only from SCHEDULED — an OPEN campaign closes normally)
 * </pre>
 */
public enum AttestationCampaignStatus {
    SCHEDULED,
    OPEN,
    CLOSED,
    CANCELLED
}
