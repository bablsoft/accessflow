package com.bablsoft.accessflow.attestation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a single attestation item — one access grant under review, snapshotted at campaign
 * open. The permission fields are the frozen snapshot and remain valid even after the underlying
 * grant is revoked or deleted.
 */
public record AttestationItemView(
        UUID id,
        UUID campaignId,
        UUID organizationId,
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
}
