package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.audit.api.BehaviorAuditSample;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorFeatureExtractorTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T01:00:00Z");

    private final BehaviorFeatureExtractor extractor =
            new BehaviorFeatureExtractor(Clock.fixed(START, ZoneOffset.UTC));

    private static BehaviorAuditSample sample(String hourUtc, boolean success, String type,
                                              List<String> tables, Long rows) {
        return new BehaviorAuditSample(Instant.parse(hourUtc), success, type, tables, rows);
    }

    @Test
    void emptySamplesYieldZeroCountsAndNullRowsMean() {
        var features = extractor.extract(List.of(), START, END);
        assertThat(features.queryCount()).isZero();
        assertThat(features.distinctTables()).isZero();
        assertThat(features.tables()).isEmpty();
        assertThat(features.queryTypeCounts()).isEmpty();
        assertThat(features.errorRate()).isZero();
        assertThat(features.meanRowsReturned()).isNull();
        assertThat(features.activeHours()).isEmpty();
        assertThat(features.hourHistogram()).hasSize(24).containsOnly(0);
        assertThat(features.windowStart()).isEqualTo(START);
        assertThat(features.windowEnd()).isEqualTo(END);
    }

    @Test
    void bucketsHoursInClockZoneAndTracksActiveHours() {
        var samples = List.of(
                sample("2026-01-01T09:15:00Z", true, "SELECT", List.of("a"), 5L),
                sample("2026-01-01T09:45:00Z", true, "SELECT", List.of("a"), 7L),
                sample("2026-01-01T14:00:00Z", true, "SELECT", List.of("b"), 3L));

        var features = extractor.extract(samples, START, END);

        assertThat(features.hourHistogram()[9]).isEqualTo(2);
        assertThat(features.hourHistogram()[14]).isEqualTo(1);
        assertThat(features.activeHours()).containsExactlyInAnyOrder(9, 14);
        assertThat(features.queryCount()).isEqualTo(3);
    }

    @Test
    void distinctTablesUnionsReferencedTables() {
        var samples = List.of(
                sample("2026-01-01T01:00:00Z", true, "SELECT", List.of("orders", "users"), 1L),
                sample("2026-01-01T02:00:00Z", true, "SELECT", List.of("users", "payments"), 1L));

        var features = extractor.extract(samples, START, END);

        assertThat(features.tables()).containsExactlyInAnyOrder("orders", "users", "payments");
        assertThat(features.distinctTables()).isEqualTo(3);
    }

    @Test
    void countsQueryTypesAndSkipsBlankOrNullType() {
        var samples = List.of(
                sample("2026-01-01T01:00:00Z", true, "SELECT", List.of(), 1L),
                sample("2026-01-01T02:00:00Z", true, "SELECT", List.of(), 1L),
                sample("2026-01-01T03:00:00Z", true, "UPDATE", List.of(), 1L),
                sample("2026-01-01T04:00:00Z", true, "   ", List.of(), 1L),
                sample("2026-01-01T05:00:00Z", true, null, List.of(), 1L));

        var features = extractor.extract(samples, START, END);

        assertThat(features.queryTypeCounts()).containsEntry("SELECT", 2).containsEntry("UPDATE", 1);
        assertThat(features.queryTypeCounts()).doesNotContainKey("   ").hasSize(2);
    }

    @Test
    void errorRateIsRatioOfFailedSamples() {
        var samples = List.of(
                sample("2026-01-01T01:00:00Z", true, "SELECT", List.of(), 1L),
                sample("2026-01-01T02:00:00Z", false, "SELECT", List.of(), null),
                sample("2026-01-01T03:00:00Z", false, "SELECT", List.of(), null),
                sample("2026-01-01T04:00:00Z", true, "SELECT", List.of(), 1L));

        var features = extractor.extract(samples, START, END);

        assertThat(features.errorRate()).isEqualTo(0.5);
    }

    @Test
    void meanRowsReturnedAveragesOnlySuccessfulSamplesWithRowCounts() {
        var samples = List.of(
                sample("2026-01-01T01:00:00Z", true, "SELECT", List.of(), 10L),
                sample("2026-01-01T02:00:00Z", true, "SELECT", List.of(), 20L),
                // failed sample with rows is ignored
                sample("2026-01-01T03:00:00Z", false, "SELECT", List.of(), 999L),
                // successful sample without a row count is ignored
                sample("2026-01-01T04:00:00Z", true, "SELECT", List.of(), null));

        var features = extractor.extract(samples, START, END);

        assertThat(features.meanRowsReturned()).isEqualTo(15.0);
    }

    @Test
    void meanRowsReturnedNullWhenNoSuccessfulSampleReportsRows() {
        var samples = List.of(
                sample("2026-01-01T01:00:00Z", true, "SELECT", List.of(), null),
                sample("2026-01-01T02:00:00Z", false, "SELECT", List.of(), 5L));

        var features = extractor.extract(samples, START, END);

        assertThat(features.meanRowsReturned()).isNull();
        assertThat(features.errorRate()).isEqualTo(0.5);
    }
}
