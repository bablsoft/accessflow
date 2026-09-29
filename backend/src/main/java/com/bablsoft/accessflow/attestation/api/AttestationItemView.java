package com.bablsoft.accessflow.attestation.api;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.core.api.PrincipalType;

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
 *
 * <p>The {@code subjectPrincipalType} / {@code subjectOwner*} fields (#875) are resolved at read
 * time, not snapshotted: a reviewer must recognise a service account for what it is and see the
 * person who owns it, or they revoke grants they do not recognise. Null when the subject no longer
 * exists, or (owner fields) when the account has no owner.
 *
 * <p>{@code rowLimitOverride} is the reviewed grant's own configured cap; {@code effectiveRowLimit}
 * is the cap enforcement applied to the subject at campaign open, after the merge across direct and
 * group grants and the datasource / deployment clamp, and {@code rowLimitSource} names what set it —
 * {@code grant}, {@code group:<name>}, {@code datasource_cap} or {@code global_ceiling} (#1084). The
 * last two are null together on items snapshotted before that evidence existed.
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
        Integer rowLimitOverride,
        Integer effectiveRowLimit,
        String rowLimitSource,
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
        Instant createdAt,
        PrincipalType subjectPrincipalType,
        String subjectOwnerEmail,
        String subjectOwnerDisplayName) {

    /** Snapshot shape without the row-limit evidence (#1084) or read-time subject (#875). */
    public AttestationItemView(UUID id, UUID campaignId, UUID organizationId, UUID permissionId,
                               UUID datasourceId, String datasourceName, UUID subjectUserId,
                               String subjectUserEmail, String subjectUserDisplayName, boolean canRead,
                               boolean canWrite, boolean canDdl, boolean canBreakGlass,
                               Instant permissionExpiresAt, Instant permissionCreatedAt,
                               Instant usageLastUsedAt, Long usageCount, Integer usageGrantedTargetCount,
                               Integer usageUsedTargetCount, GrantUsageRecommendation usageRecommendation,
                               AttestationItemDecision decision, AttestationItemCloseReason closeReason,
                               UUID decidedBy, Instant decidedAt, String decisionComment, Instant createdAt) {
        this(id, campaignId, organizationId, permissionId, datasourceId, datasourceName, subjectUserId,
                subjectUserEmail, subjectUserDisplayName, canRead, canWrite, canDdl, canBreakGlass,
                permissionExpiresAt, permissionCreatedAt, null, null, null, usageLastUsedAt, usageCount,
                usageGrantedTargetCount, usageUsedTargetCount, usageRecommendation, decision, closeReason,
                decidedBy, decidedAt, decisionComment, createdAt, null, null, null);
    }

    /** The same item with its subject resolved (#875). */
    public AttestationItemView withSubject(PrincipalType principalType, String ownerEmail,
                                           String ownerDisplayName) {
        return new AttestationItemView(id, campaignId, organizationId, permissionId, datasourceId,
                datasourceName, subjectUserId, subjectUserEmail, subjectUserDisplayName, canRead, canWrite,
                canDdl, canBreakGlass, permissionExpiresAt, permissionCreatedAt, rowLimitOverride,
                effectiveRowLimit, rowLimitSource, usageLastUsedAt,
                usageCount, usageGrantedTargetCount, usageUsedTargetCount, usageRecommendation, decision,
                closeReason, decidedBy, decidedAt, decisionComment, createdAt, principalType, ownerEmail,
                ownerDisplayName);
    }
}
