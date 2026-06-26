package com.bablsoft.accessflow.attestation.api;

/**
 * Which grants a campaign snapshots at open time: every active datasource in the organization
 * ({@link #ORGANIZATION}) or a single datasource ({@link #DATASOURCE}).
 */
public enum AttestationCampaignScope {
    ORGANIZATION,
    DATASOURCE
}
