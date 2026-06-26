package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignView;
import com.bablsoft.accessflow.attestation.api.AttestationPendingDefault;

import java.time.Instant;
import java.util.UUID;

public record AttestationCampaignResponse(
        UUID id,
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

    public static AttestationCampaignResponse from(AttestationCampaignView v) {
        return new AttestationCampaignResponse(v.id(), v.name(), v.description(), v.scope(),
                v.datasourceId(), v.datasourceName(), v.status(), v.pendingDefault(),
                v.scheduledOpenAt(), v.dueAt(), v.openedAt(), v.closedAt(), v.totalItems(),
                v.pendingItems(), v.certifiedItems(), v.revokedItems(), v.createdBy(),
                v.createdAt());
    }
}
