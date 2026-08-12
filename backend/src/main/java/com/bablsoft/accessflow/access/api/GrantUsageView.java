package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.GrantResourceKind;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Usage evidence for one standing grant (#625): when it was last exercised, how often, and how much
 * of its granted scope it actually touches.
 *
 * <p>{@code grantedTargetCount} is {@code null} when the grant is <em>unrestricted</em> (it allows
 * every table / operation). That is different from zero, and callers must not treat it as a
 * denominator — an unrestricted grant can never be reported over-scoped, because there is no
 * granted scope to under-use.
 */
public record GrantUsageView(
        UUID id,
        UUID organizationId,
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
        long usageCount,
        Instant firstUsedAt,
        Instant lastUsedAt,
        Instant observedSince,
        GrantUsageRecommendation recommendation) {

    public GrantUsageView {
        usedTargets = usedTargets == null ? List.of() : List.copyOf(usedTargets);
    }

    /**
     * Whole days since the grant was last exercised, or {@code null} when it never has been —
     * "never" is not "a very large number of days", and the read side renders the two differently.
     */
    public Long daysSinceLastUse(Instant now) {
        return lastUsedAt == null ? null : Duration.between(lastUsedAt, now).toDays();
    }

    /**
     * Average uses per week over this grant's observation window, or {@code null} when the window is
     * shorter than a day (any rate computed over minutes is noise, not a frequency).
     */
    public Double usagePerWeek(Instant now) {
        var observed = Duration.between(observedSince, now);
        if (observed.toDays() < 1) {
            return null;
        }
        return usageCount * 7.0d / observed.toDays();
    }

    /**
     * Granted tables/operations never exercised, or {@code null} for an unrestricted grant. Never
     * negative: a grant whose scope shrank after the fact can leave more used than granted.
     */
    public Integer unusedTargetCount() {
        return grantedTargetCount == null ? null : Math.max(0, grantedTargetCount - usedTargetCount);
    }
}
