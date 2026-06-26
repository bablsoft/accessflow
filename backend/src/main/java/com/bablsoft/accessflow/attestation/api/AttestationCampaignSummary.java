package com.bablsoft.accessflow.attestation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal campaign descriptor used by cross-module readers (notifications, realtime fan-out) that
 * only need to label and link a campaign, not render its full state.
 */
public record AttestationCampaignSummary(
        UUID id,
        UUID organizationId,
        String name,
        Instant dueAt) {
}
