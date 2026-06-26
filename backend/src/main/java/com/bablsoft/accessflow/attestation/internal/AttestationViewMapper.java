package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignSummary;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignView;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;

/** Maps attestation entities to their {@code api/} read models. */
final class AttestationViewMapper {

    private AttestationViewMapper() {
    }

    static AttestationItemView toItemView(AttestationItemEntity e) {
        return new AttestationItemView(
                e.getId(),
                e.getCampaignId(),
                e.getOrganizationId(),
                e.getPermissionId(),
                e.getDatasourceId(),
                e.getDatasourceName(),
                e.getSubjectUserId(),
                e.getSubjectUserEmail(),
                e.getSubjectUserDisplayName(),
                e.isCanRead(),
                e.isCanWrite(),
                e.isCanDdl(),
                e.isCanBreakGlass(),
                e.getPermissionExpiresAt(),
                e.getPermissionCreatedAt(),
                e.getDecision(),
                e.getCloseReason(),
                e.getDecidedBy(),
                e.getDecidedAt(),
                e.getDecisionComment(),
                e.getCreatedAt());
    }

    static AttestationCampaignView toCampaignView(AttestationCampaignEntity e, String datasourceName,
                                                  int pending, int certified, int revoked) {
        return new AttestationCampaignView(
                e.getId(),
                e.getOrganizationId(),
                e.getName(),
                e.getDescription(),
                e.getScope(),
                e.getDatasourceId(),
                datasourceName,
                e.getStatus(),
                e.getPendingDefault(),
                e.getScheduledOpenAt(),
                e.getDueAt(),
                e.getOpenedAt(),
                e.getClosedAt(),
                e.getTotalItems(),
                pending,
                certified,
                revoked,
                e.getCreatedBy(),
                e.getCreatedAt());
    }

    static AttestationCampaignSummary toSummary(AttestationCampaignEntity e) {
        return new AttestationCampaignSummary(e.getId(), e.getOrganizationId(), e.getName(),
                e.getDueAt());
    }
}
