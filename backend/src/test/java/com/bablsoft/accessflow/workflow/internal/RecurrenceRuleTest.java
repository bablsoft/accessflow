package com.bablsoft.accessflow.workflow.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceRuleTest {

    // 2026-08-10 is a Monday.
    private static final Instant MONDAY_0700_UTC = Instant.parse("2026-08-10T07:00:00Z");
    private static final Instant MONDAY_0800_UTC = Instant.parse("2026-08-10T08:00:00Z");
    private static final Instant MONDAY_0900_UTC = Instant.parse("2026-08-10T09:00:00Z");
    private static final Instant NEXT_MONDAY_0800_UTC = Instant.parse("2026-08-17T08:00:00Z");

    @Test
    void parseUppercasePrefixedDurationYieldsFixedInterval() {
        var rule = RecurrenceRule.parse("PT6H");

        assertThat(rule).isInstanceOf(RecurrenceRule.FixedInterval.class);
        assertThat(((RecurrenceRule.FixedInterval) rule).interval())
                .isEqualTo(Duration.ofHours(6));
    }

    @Test
    void parseLowercasePrefixedDurationYieldsFixedInterval() {
        var rule = RecurrenceRule.parse("p1d");

        assertThat(rule).isInstanceOf(RecurrenceRule.FixedInterval.class);
        assertThat(((RecurrenceRule.FixedInterval) rule).interval())
                .isEqualTo(Duration.ofDays(1));
    }

    @Test
    void parseSixFieldCronYieldsCron() {
        var rule = RecurrenceRule.parse("0 0 8 * * MON");

        assertThat(rule).isInstanceOf(RecurrenceRule.Cron.class);
    }

    @Test
    void parseRejectsNull() {
        assertThatThrownBy(() -> RecurrenceRule.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsBlank() {
        assertThatThrownBy(() -> RecurrenceRule.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsZeroDuration() {
        assertThatThrownBy(() -> RecurrenceRule.parse("PT0S"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsNegativeDuration() {
        assertThatThrownBy(() -> RecurrenceRule.parse("PT-6H"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsGarbageCron() {
        assertThatThrownBy(() -> RecurrenceRule.parse("not a cron"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsFiveFieldCron() {
        assertThatThrownBy(() -> RecurrenceRule.parse("0 8 * * MON"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextAfterForIntervalAddsTheInterval() {
        var rule = RecurrenceRule.parse("PT6H");

        assertThat(rule.nextAfter(MONDAY_0700_UTC))
                .isEqualTo(MONDAY_0700_UTC.plus(Duration.ofHours(6)));
    }

    @Test
    void nextAfterForCronBeforeTheMatchFiresSameDayInUtc() {
        var rule = RecurrenceRule.parse("0 0 8 * * MON");

        assertThat(rule.nextAfter(MONDAY_0700_UTC)).isEqualTo(MONDAY_0800_UTC);
    }

    @Test
    void nextAfterForCronPastTheMatchRollsToNextWeekInUtc() {
        var rule = RecurrenceRule.parse("0 0 8 * * MON");

        assertThat(rule.nextAfter(MONDAY_0900_UTC)).isEqualTo(NEXT_MONDAY_0800_UTC);
    }

    @Test
    void minGapForIntervalReturnsTheInterval() {
        var rule = RecurrenceRule.parse("PT6H");

        assertThat(rule.minGap(MONDAY_0700_UTC)).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void minGapForWeeklyCronIsSevenDays() {
        var rule = RecurrenceRule.parse("0 0 8 * * MON");

        assertThat(rule.minGap(MONDAY_0700_UTC)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void minGapForEveryMinuteCronIsOneMinute() {
        var rule = RecurrenceRule.parse("0 * * * * *");

        assertThat(rule.minGap(MONDAY_0700_UTC)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void minGapForIrregularCronFindsTheTightestGapNotJustTheFirst() {
        // Fires daily at 08:00 and 08:01 — the first sampled gap from 07:00 is 1 minute, but a
        // naive two-occurrence sample starting mid-cycle would see ~24h. The floor check must
        // find the 1-minute gap regardless of where sampling starts.
        var rule = RecurrenceRule.parse("0 0,1 8 * * *");

        assertThat(rule.minGap(MONDAY_0700_UTC)).isEqualTo(Duration.ofMinutes(1));
        assertThat(rule.minGap(MONDAY_0900_UTC)).isEqualTo(Duration.ofMinutes(1));
    }
}
