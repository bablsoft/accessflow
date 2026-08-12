package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * One attestation item as returned to a reviewer.
 *
 * <p>{@code @JsonInclude(ALWAYS)} overrides the global {@code non_null} default so the {@code usage*}
 * keys (#625) are always present. They are null together when no usage evidence was available at
 * campaign open, and the worklist must be able to tell that apart from "never used" — an omitted key
 * makes the two indistinguishable, and the wrong reading nudges a reviewer toward revoking a grant
 * nothing is known about.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
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

    public static AttestationItemResponse from(AttestationItemView v) {
        return new AttestationItemResponse(v.id(), v.campaignId(), v.permissionId(),
                v.datasourceId(), v.datasourceName(), v.subjectUserId(), v.subjectUserEmail(),
                v.subjectUserDisplayName(), v.canRead(), v.canWrite(), v.canDdl(), v.canBreakGlass(),
                v.permissionExpiresAt(), v.permissionCreatedAt(), v.usageLastUsedAt(),
                v.usageCount(), v.usageGrantedTargetCount(), v.usageUsedTargetCount(),
                v.usageRecommendation(), v.decision(), v.closeReason(), v.decidedBy(), v.decidedAt(),
                v.decisionComment(), v.createdAt());
    }
}
