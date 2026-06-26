package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;

import java.time.Instant;
import java.util.UUID;

public record AttestationItemResponse(
        UUID id,
        UUID campaignId,
        UUID permissionId,
        UUID datasourceId,
        String datasourceName,
        UUID subjectUserId,
        String subjectUserEmail,
        String subjectUserDisplayName,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        boolean canBreakGlass,
        Instant permissionExpiresAt,
        Instant permissionCreatedAt,
        AttestationItemDecision decision,
        AttestationItemCloseReason closeReason,
        UUID decidedBy,
        Instant decidedAt,
        String decisionComment,
        Instant createdAt) {

    public static AttestationItemResponse from(AttestationItemView v) {
        return new AttestationItemResponse(v.id(), v.campaignId(), v.permissionId(),
                v.datasourceId(), v.datasourceName(), v.subjectUserId(), v.subjectUserEmail(),
                v.subjectUserDisplayName(), v.canRead(), v.canWrite(), v.canDdl(), v.canBreakGlass(),
                v.permissionExpiresAt(), v.permissionCreatedAt(), v.decision(), v.closeReason(),
                v.decidedBy(), v.decidedAt(), v.decisionComment(), v.createdAt());
    }
}
