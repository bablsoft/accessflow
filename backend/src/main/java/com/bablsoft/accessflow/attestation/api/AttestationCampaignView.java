package com.bablsoft.accessflow.attestation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of an attestation campaign exposed to other modules and (via a web response mapper)
 * the API. {@code itemCounts} are populated for single-campaign reads and may be zero on list rows
 * where aggregation is skipped.
 */
public record AttestationCampaignView(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        AttestationCampaignScope scope,
        UUID datasourceId,
        String datasourceName,
        AttestationCampaignStatus status,
        AttestationPendingDefault pendingDefault,
        Instant scheduledOpenAt,
        Instant dueAt,
        Instant openedAt,
        Instant closedAt,
        int totalItems,
        int pendingItems,
        int certifiedItems,
        int revokedItems,
        UUID createdBy,
        Instant createdAt) {
}
