package com.bablsoft.accessflow.attestation.events;

import java.util.UUID;

/**
 * Published when a campaign transitions SCHEDULED → OPEN and its items have been snapshotted.
 * Consumed by the notifications module (fan-out to eligible reviewers) and the realtime module
 * (WebSocket {@code attestation.campaign_opened}).
 */
public record AttestationCampaignOpenedEvent(UUID campaignId, UUID organizationId) {
}
