package com.bablsoft.accessflow.attestation.api;

/**
 * Thrown when a campaign or item is acted on in a state that forbids the requested transition
 * (e.g. cancelling an already-OPEN campaign, or working an item of a non-OPEN campaign).
 */
public class IllegalAttestationCampaignTransitionException extends AttestationException {

    private final AttestationCampaignStatus currentStatus;

    public IllegalAttestationCampaignTransitionException(AttestationCampaignStatus currentStatus,
                                                         String message) {
        super(message);
        this.currentStatus = currentStatus;
    }

    public AttestationCampaignStatus currentStatus() {
        return currentStatus;
    }
}
