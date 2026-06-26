package com.bablsoft.accessflow.attestation.api;

import java.util.UUID;

public class AttestationCampaignNotFoundException extends AttestationException {

    public AttestationCampaignNotFoundException(UUID campaignId) {
        super("Attestation campaign not found: " + campaignId);
    }
}
