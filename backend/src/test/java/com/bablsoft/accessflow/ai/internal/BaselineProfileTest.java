package com.bablsoft.accessflow.ai.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineProfileTest {

    private static WindowFeatures window(int queryCount, int distinctTables, double errorRate,
                                         Double meanRows, int[] hourHistogram, Set<String> tables,
                                         Map<String, Integer> queryTypeCounts, Set<Integer> activeHours) {
        return new WindowFeatures(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T01:00:00Z"), queryCount, hourHistogram, distinctTables,
                tables, queryTypeCounts, meanRows, errorRate, activeHours);
    }

    @Test
    void emptyHasNoScalarsZeroHistogramAndZeroWindows() {
        var profile = BaselineProfile.empty();
        assertThat(profile.scalars()).isEmpty();
        assertThat(profile.hourHistogram()).hasSize(24).containsOnly(0L);
        assertThat(profile.queryTypeFrequencies()).isEmpty();
        assertThat(profile.tableFrequencies()).isEmpty();
        assertThat(profile.windowsFolded()).isZero();
    }

    @Test
    void scalarReturnsEmptyForUnknownFeature() {
        assertThat(BaselineProfile.empty().scalar(BaselineProfile.QUERY_COUNT)).isEmpty();
    }

    @Test
    void compactConstructorReplacesNullCollectionsAndFixesHistogramLength() {
        var profile = new BaselineProfile(null, new long[5], null, null, 3);
        assertThat(profile.scalars()).isNotNull().isEmpty();
        assertThat(profile.queryTypeFrequencies()).isNotNull().isEmpty();
        assertThat(profile.tableFrequencies()).isNotNull().isEmpty();
        assertThat(profile.hourHistogram()).hasSize(24);
        assertThat(profile.windowsFolded()).isEqualTo(3);
    }

    @Test
    void compactConstructorTruncatesOverlongHistogramKeepingPrefix() {
        var raw = new long[30];
        raw[0] = 7L;
        raw[23] = 9L;
        var profile = new BaselineProfile(Map.of(), raw, Map.of(), Map.of(), 0);
        assertThat(profile.hourHistogram()).hasSize(24);
        assertThat(profile.hourHistogram()[0]).isEqualTo(7L);
        assertThat(profile.hourHistogram()[23]).isEqualTo(9L);
    }

    @Test
    void compactConstructorKeepsValidHistogram() {
        var raw = new long[24];
        raw[10] = 4L;
        var profile = new BaselineProfile(Map.of(), raw, Map.of(), Map.of(), 0);
        assertThat(profile.hourHistogram()).isSameAs(raw);
    }

    @Test
    void foldAppendsScalarsMergesMapsAndIncrementsWindowCount() {
        var hist = new int[24];
        hist[9] = 2;
        var window = window(5, 3, 0.2, 100.0, hist, Set.of("orders", "users"),
                Map.of("SELECT", 4, "UPDATE", 1), Set.of(9));

        var folded = BaselineProfile.empty().fold(window, 90);

        assertThat(folded.scalar(BaselineProfile.QUERY_COUNT)).containsExactly(5.0);
        assertThat(folded.scalar(BaselineProfile.DISTINCT_TABLES)).containsExactly(3.0);
        assertThat(folded.scalar(BaselineProfile.ERROR_RATE)).containsExactly(0.2);
        assertThat(folded.scalar(BaselineProfile.ROWS_RETURNED)).containsExactly(100.0);
        assertThat(folded.hourHistogram()[9]).isEqualTo(2L);
        assertThat(folded.queryTypeFrequencies()).containsEntry("SELECT", 4L).containsEntry("UPDATE", 1L);
        assertThat(folded.tableFrequencies()).containsEntry("orders", 1L).containsEntry("users", 1L);
        assertThat(folded.windowsFolded()).isEqualTo(1);
    }

    @Test
    void foldDoesNotAppendRowsReturnedWhenWindowMeanIsNull() {
        var window = window(2, 1, 0.0, null, new int[24], Set.of("t"), Map.of("SELECT", 2), Set.of(0));
        var folded = BaselineProfile.empty().fold(window, 90);
        assertThat(folded.scalar(BaselineProfile.ROWS_RETURNED)).isEmpty();
        assertThat(folded.scalar(BaselineProfile.QUERY_COUNT)).containsExactly(2.0);
    }

    @Test
    void foldAccumulatesAcrossWindowsAndMergesFrequencies() {
        var w1 = window(3, 1, 0.0, 10.0, new int[24], Set.of("a"), Map.of("SELECT", 3), Set.of(1));
        var w2 = window(7, 2, 0.5, 20.0, new int[24], Set.of("a", "b"),
                Map.of("SELECT", 5, "DELETE", 2), Set.of(2));

        var folded = BaselineProfile.empty().fold(w1, 90).fold(w2, 90);

        assertThat(folded.scalar(BaselineProfile.QUERY_COUNT)).containsExactly(3.0, 7.0);
        assertThat(folded.queryTypeFrequencies()).containsEntry("SELECT", 8L).containsEntry("DELETE", 2L);
        assertThat(folded.tableFrequencies()).containsEntry("a", 2L).containsEntry("b", 1L);
        assertThat(folded.windowsFolded()).isEqualTo(2);
    }

    @Test
    void foldTrimsScalarListToMaxSamplesFifo() {
        var profile = BaselineProfile.empty();
        for (int i = 1; i <= 5; i++) {
            var window = window(i, 0, 0.0, null, new int[24], Set.of(), Map.of(), Set.of());
            profile = profile.fold(window, 3);
        }
        // FIFO trim keeps the three most recent query-count observations: 3,4,5.
        assertThat(profile.scalar(BaselineProfile.QUERY_COUNT)).containsExactly(3.0, 4.0, 5.0);
        assertThat(profile.windowsFolded()).isEqualTo(5);
    }

    @Test
    void foldHandlesShortWindowHistogramWithoutIndexError() {
        var shortHist = new int[10];
        shortHist[5] = 3;
        var window = new WindowFeatures(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T01:00:00Z"), 1, shortHist, 0, Set.of(),
                new LinkedHashMap<>(), null, 0.0, Set.of(5));
        var folded = BaselineProfile.empty().fold(window, 90);
        assertThat(folded.hourHistogram()[5]).isEqualTo(3L);
    }
}
