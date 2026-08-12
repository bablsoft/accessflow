package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.api.GrantUsageView;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the over-provisioned access report (#625).
 *
 * <p>The nullable numbers are deliberately nullable and must not be defaulted to zero on the way
 * out: a null {@code grantedTargetCount} / {@code unusedTargetCount} means the grant is
 * unrestricted, and a null {@code daysSinceLastUse} means it has never been used — both are
 * different facts from "zero", and the UI renders them differently.
 *
 * <p>{@code @JsonInclude(ALWAYS)} overrides the global {@code non_null} default for exactly that
 * reason (same rationale as {@code QueryDiffResponse}): "not applicable" is information the client
 * needs, and an omitted key is far easier to misread as an unset optional than an explicit null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OverProvisionedAccessResponse(
        UUID id,
        GrantResourceKind resourceKind,
        UUID resourceId,
        String resourceName,
        UUID permissionId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        Instant grantedAt,
        Instant expiresAt,
        Integer grantedTargetCount,
        List<String> usedTargets,
        int usedTargetCount,
        Integer unusedTargetCount,
        long usageCount,
        Instant firstUsedAt,
        Instant lastUsedAt,
        Instant observedSince,
        Long daysSinceLastUse,
        Double usagePerWeek,
        GrantUsageRecommendation recommendation) {

    public static OverProvisionedAccessResponse from(GrantUsageView view, Instant now) {
        return new OverProvisionedAccessResponse(
                view.id(),
                view.resourceKind(),
                view.resourceId(),
                view.resourceName(),
                view.permissionId(),
                view.userId(),
                view.userEmail(),
                view.userDisplayName(),
                view.grantedAt(),
                view.expiresAt(),
                view.grantedTargetCount(),
                view.usedTargets(),
                view.usedTargetCount(),
                view.unusedTargetCount(),
                view.usageCount(),
                view.firstUsedAt(),
                view.lastUsedAt(),
                view.observedSince(),
                view.daysSinceLastUse(now),
                view.usagePerWeek(now),
                view.recommendation());
    }
}
