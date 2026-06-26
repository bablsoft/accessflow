package com.bablsoft.accessflow.attestation.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-module read facade for the notifications and realtime modules: fetch a campaign label/link
 * and resolve the set of users who should be notified when a campaign opens (eligible reviewers
 * across the campaign's datasources, plus active org admins).
 */
public interface AttestationCampaignLookupService {

    Optional<AttestationCampaignSummary> findSummary(UUID campaignId);

    Set<UUID> findRecipientUserIds(UUID campaignId);
}
