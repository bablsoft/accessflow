package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySuggestionScorerTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    private QuerySuggestionScorer scorer(double freq, double recency, double overlap) {
        return new QuerySuggestionScorer(new QuerySuggestionProperties(true, null, null, 0, 0, 0, 0,
                0, Duration.ofDays(14), freq, recency, overlap, 0, 0, null));
    }

    @Test
    void frequencyTermIsLogarithmicAndMonotonic() {
        var scorer = scorer(1, 0, 0);
        double one = scorer.score(1, NOW, NOW, Set.of(), Set.of());
        double ten = scorer.score(10, NOW, NOW, Set.of(), Set.of());
        double hundred = scorer.score(100, NOW, NOW, Set.of(), Set.of());
        assertThat(one).isLessThan(ten);
        assertThat(ten).isLessThan(hundred);
        // Logarithmic, so ten times the runs is nowhere near ten times the score.
        assertThat(hundred).isLessThan(one + 10);
        assertThat(one).isEqualTo(Math.log(2), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void zeroAndNegativeApprovedCountsScoreZeroFrequency() {
        var scorer = scorer(1, 0, 0);
        assertThat(scorer.score(0, NOW, NOW, Set.of(), Set.of())).isZero();
        assertThat(scorer.score(-5, NOW, NOW, Set.of(), Set.of())).isZero();
    }

    @Test
    void recencyHalvesAtExactlyOneHalfLife() {
        var scorer = scorer(0, 1, 0);
        var oneHalfLifeAgo = NOW.minus(Duration.ofDays(14));
        assertThat(scorer.score(0, oneHalfLifeAgo, NOW, Set.of(), Set.of()))
                .isEqualTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        var twoHalfLivesAgo = NOW.minus(Duration.ofDays(28));
        assertThat(scorer.score(0, twoHalfLivesAgo, NOW, Set.of(), Set.of()))
                .isEqualTo(0.25, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void brandNewAndFutureStampedRowsBothScoreOne() {
        var scorer = scorer(0, 1, 0);
        assertThat(scorer.score(0, NOW, NOW, Set.of(), Set.of())).isEqualTo(1);
        // Clock skew between replicas must not let a row score above a brand-new one.
        assertThat(scorer.score(0, NOW.plusSeconds(600), NOW, Set.of(), Set.of())).isEqualTo(1);
    }

    @Test
    void missingTimestampsContributeNoRecency() {
        var scorer = scorer(0, 1, 0);
        assertThat(scorer.score(0, null, NOW, Set.of(), Set.of())).isZero();
        assertThat(scorer.score(0, NOW, null, Set.of(), Set.of())).isZero();
    }

    @Test
    void overlapIsJaccardAndIsZeroWhenEitherSideIsEmptyOrDisjoint() {
        var scorer = scorer(0, 0, 1);
        assertThat(scorer.score(0, NOW, NOW, Set.of("orders"), Set.of("orders"))).isEqualTo(1);
        // |{orders}| / |{orders, users, refunds}|
        assertThat(scorer.score(0, NOW, NOW, Set.of("orders", "users"), Set.of("orders", "refunds")))
                .isEqualTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(scorer.score(0, NOW, NOW, Set.of("orders"), Set.of("users"))).isZero();
        assertThat(scorer.score(0, NOW, NOW, Set.of("orders"), Set.of())).isZero();
        assertThat(scorer.score(0, NOW, NOW, Set.of(), Set.of("orders"))).isZero();
        assertThat(scorer.score(0, NOW, NOW, null, Set.of("orders"))).isZero();
        assertThat(scorer.score(0, NOW, NOW, Set.of("orders"), null)).isZero();
    }

    @Test
    void weightsScaleTheirOwnTermOnly() {
        var doubled = scorer(2, 0, 0);
        var single = scorer(1, 0, 0);
        assertThat(doubled.score(9, NOW, NOW, Set.of(), Set.of()))
                .isEqualTo(2 * single.score(9, NOW, NOW, Set.of(), Set.of()),
                        org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void termsSumTogether() {
        var scorer = scorer(1, 1, 1);
        var oneHalfLifeAgo = NOW.minus(Duration.ofDays(14));
        assertThat(scorer.score(1, oneHalfLifeAgo, NOW, Set.of("orders"), Set.of("orders")))
                .isEqualTo(Math.log(2) + 0.5 + 1.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
