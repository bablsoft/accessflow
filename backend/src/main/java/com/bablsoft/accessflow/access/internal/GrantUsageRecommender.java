package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;

import java.time.Duration;
import java.time.Instant;

/**
 * Turns usage evidence into a revocation recommendation (#625). A pure function of its inputs — no
 * Spring, no repository, no clock of its own — so every verdict and boundary is unit-testable.
 *
 * <p>Two conservatism rules shape the ordering below, and both exist to avoid recommending
 * revocation of a grant that is genuinely in use:
 *
 * <ul>
 *   <li><strong>Recent use wins over youth.</strong> A grant created yesterday and used today is
 *       {@code ACTIVE}, not {@code INSUFFICIENT_DATA} — the observation-window guard only applies
 *       when there is nothing recent to report.</li>
 *   <li><strong>Unknown scope is never over-scope.</strong> {@code OVER_SCOPED} requires at least one
 *       granted target observed. A grant used only through audit rows written before the target
 *       enrichment landed has {@code usedTargetCount == 0} with a non-zero usage count, which reads
 *       as "used, scope unknown" — reporting that as over-scoped would be fabricating evidence.</li>
 * </ul>
 */
final class GrantUsageRecommender {

    private final Duration minObservationWindow;
    private final Duration stalenessThreshold;
    private final double overScopedThreshold;

    GrantUsageRecommender(Duration minObservationWindow, Duration stalenessThreshold,
                          double overScopedThreshold) {
        this.minObservationWindow = minObservationWindow;
        this.stalenessThreshold = stalenessThreshold;
        this.overScopedThreshold = overScopedThreshold;
    }

    /**
     * @param observedSince      start of this grant's observation window
     * @param lastUsedAt         last observed use, or null if never used
     * @param usageCount         total observed uses within the window
     * @param grantedTargetCount granted tables/operations, or null when the grant is unrestricted
     * @param usedTargetCount    granted targets actually exercised
     */
    GrantUsageRecommendation recommend(Instant now, Instant observedSince, Instant lastUsedAt,
                                       long usageCount, Integer grantedTargetCount,
                                       int usedTargetCount) {
        if (lastUsedAt != null && !isOlderThan(lastUsedAt, now, stalenessThreshold)) {
            return isOverScoped(grantedTargetCount, usedTargetCount)
                    ? GrantUsageRecommendation.OVER_SCOPED
                    : GrantUsageRecommendation.ACTIVE;
        }
        if (observedSince == null
                || !isOlderThan(observedSince, now, minObservationWindow)) {
            return GrantUsageRecommendation.INSUFFICIENT_DATA;
        }
        return usageCount == 0 ? GrantUsageRecommendation.NEVER_USED : GrantUsageRecommendation.STALE;
    }

    private boolean isOverScoped(Integer grantedTargetCount, int usedTargetCount) {
        if (grantedTargetCount == null || grantedTargetCount <= 0 || usedTargetCount <= 0) {
            return false;
        }
        return (double) usedTargetCount / grantedTargetCount < overScopedThreshold;
    }

    private static boolean isOlderThan(Instant instant, Instant now, Duration threshold) {
        return Duration.between(instant, now).compareTo(threshold) > 0;
    }
}
