package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GrantUsageRecommenderTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Duration MIN_OBSERVATION = Duration.ofDays(14);
    private static final Duration STALENESS = Duration.ofDays(60);

    private final GrantUsageRecommender recommender =
            new GrantUsageRecommender(MIN_OBSERVATION, STALENESS, 0.5);

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    private GrantUsageRecommendation recommend(Instant observedSince, Instant lastUsedAt,
                                               long usageCount, Integer granted, int used) {
        return recommender.recommend(NOW, observedSince, lastUsedAt, usageCount, granted, used);
    }

    @Test
    void neverUsedWhenObservedLongEnoughWithNoActivity() {
        assertThat(recommend(daysAgo(90), null, 0, 5, 0))
                .isEqualTo(GrantUsageRecommendation.NEVER_USED);
    }

    @Test
    void staleWhenLastUseIsOlderThanTheThreshold() {
        assertThat(recommend(daysAgo(180), daysAgo(90), 12, 5, 3))
                .isEqualTo(GrantUsageRecommendation.STALE);
    }

    @Test
    void activeWhenUsedRecentlyAcrossMostOfTheGrantedScope() {
        assertThat(recommend(daysAgo(90), daysAgo(2), 40, 4, 3))
                .isEqualTo(GrantUsageRecommendation.ACTIVE);
    }

    @Test
    void overScopedWhenUsedRecentlyButOnlyAcrossASliverOfTheGrantedScope() {
        assertThat(recommend(daysAgo(90), daysAgo(2), 40, 12, 2))
                .isEqualTo(GrantUsageRecommendation.OVER_SCOPED);
    }

    @Test
    void insufficientDataWhenTheObservationWindowIsTooShort() {
        assertThat(recommend(daysAgo(3), null, 0, 5, 0))
                .isEqualTo(GrantUsageRecommendation.INSUFFICIENT_DATA);
        assertThat(recommend(null, null, 0, 5, 0))
                .isEqualTo(GrantUsageRecommendation.INSUFFICIENT_DATA);
    }

    /** Recent use outranks youth: a grant created yesterday and used today is plainly in use. */
    @Test
    void recentUseBeatsTheObservationWindowGuard() {
        assertThat(recommend(daysAgo(1), daysAgo(0), 3, null, 1))
                .isEqualTo(GrantUsageRecommendation.ACTIVE);
    }

    /** An unrestricted grant has no allow-list to under-use, so it can never be over-scoped. */
    @Test
    void unrestrictedGrantIsNeverOverScoped() {
        assertThat(recommend(daysAgo(90), daysAgo(1), 100, null, 1))
                .isEqualTo(GrantUsageRecommendation.ACTIVE);
    }

    /**
     * Audit rows predating the target enrichment yield usage with no observed targets. Reporting
     * that as over-scoped would be inventing evidence, so it must read as ACTIVE.
     */
    @Test
    void usageWithNoObservedTargetsIsNeverOverScoped() {
        assertThat(recommend(daysAgo(90), daysAgo(1), 50, 20, 0))
                .isEqualTo(GrantUsageRecommendation.ACTIVE);
    }

    @Test
    void aGrantedCountOfZeroIsTreatedAsUnrestrictedNotAsDivisionByZero() {
        assertThat(recommend(daysAgo(90), daysAgo(1), 10, 0, 0))
                .isEqualTo(GrantUsageRecommendation.ACTIVE);
    }

    @Test
    void stalenessBoundaryIsExclusive() {
        // Exactly at the threshold still counts as recent; one day past it does not.
        assertThat(recommend(daysAgo(180), NOW.minus(STALENESS), 5, null, 1))
                .isEqualTo(GrantUsageRecommendation.ACTIVE);
        assertThat(recommend(daysAgo(180), NOW.minus(STALENESS).minus(Duration.ofDays(1)), 5, null, 1))
                .isEqualTo(GrantUsageRecommendation.STALE);
    }

    @Test
    void observationBoundaryIsExclusive() {
        assertThat(recommend(NOW.minus(MIN_OBSERVATION), null, 0, null, 0))
                .isEqualTo(GrantUsageRecommendation.INSUFFICIENT_DATA);
        assertThat(recommend(NOW.minus(MIN_OBSERVATION).minus(Duration.ofDays(1)), null, 0, null, 0))
                .isEqualTo(GrantUsageRecommendation.NEVER_USED);
    }

    @Test
    void overScopedRatioBoundaryIsExclusive() {
        // 2/4 == the 0.5 threshold, so not over-scoped; 1/4 is below it.
        assertThat(recommend(daysAgo(90), daysAgo(1), 10, 4, 2))
                .isEqualTo(GrantUsageRecommendation.ACTIVE);
        assertThat(recommend(daysAgo(90), daysAgo(1), 10, 4, 1))
                .isEqualTo(GrantUsageRecommendation.OVER_SCOPED);
    }

    /** A grant whose allow-list shrank can show more used than granted; that is not over-scope. */
    @Test
    void moreUsedThanGrantedIsNotOverScoped() {
        assertThat(recommend(daysAgo(90), daysAgo(1), 10, 2, 5))
                .isEqualTo(GrantUsageRecommendation.ACTIVE);
    }
}
