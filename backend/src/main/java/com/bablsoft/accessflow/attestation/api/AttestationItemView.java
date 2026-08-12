package com.bablsoft.accessflow.attestation.api;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a single attestation item — one access grant under review, snapshotted at campaign
 * open. The permission fields are the frozen snapshot and remain valid even after the underlying
 * grant is revoked or deleted.
 *
 * <p>The {@code usage*} fields are the least-privilege evidence (#625) captured at the same moment,
 * so a reviewer sees whether the grant is actually exercised instead of deciding blind. They are
 * <strong>all nullable together</strong>, and null means "no usage data" — not "never used". A
 * grant first summarised after its campaign opened has none, and rendering that as "never used"
 * would push a reviewer toward revoking a grant nothing is known about.
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
        Instant usageLastUsedAt,
        Long usageCount,
        Integer usageGrantedTargetCount,
        Integer usageUsedTargetCount,
        GrantUsageRecommendation usageRecommendation,
        AttestationItemDecision decision,
        AttestationItemCloseReason closeReason,
        UUID decidedBy,
        Instant decidedAt,
        String decisionComment,
        Instant createdAt) {
}
