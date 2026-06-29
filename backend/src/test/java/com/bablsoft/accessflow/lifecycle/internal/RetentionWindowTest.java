package com.bablsoft.accessflow.lifecycle.internal;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionWindowTest {

    private static final ZonedDateTime NOW =
            ZonedDateTime.parse("2026-06-29T00:00:00Z").withZoneSameInstant(ZoneOffset.UTC);

    @Test
    void parsesIsoPeriodDays() {
        assertThat(RetentionWindow.parse("P30D").cutoffFrom(NOW))
                .isEqualTo(ZonedDateTime.parse("2026-05-30T00:00:00Z"));
    }

    @Test
    void parsesIsoPeriodYears() {
        assertThat(RetentionWindow.parse("P7Y").cutoffFrom(NOW))
                .isEqualTo(ZonedDateTime.parse("2019-06-29T00:00:00Z"));
    }

    @Test
    void parsesIsoDuration() {
        assertThat(RetentionWindow.parse("PT24H").cutoffFrom(NOW))
                .isEqualTo(ZonedDateTime.parse("2026-06-28T00:00:00Z"));
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> RetentionWindow.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> RetentionWindow.parse("30 days"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroPeriod() {
        assertThatThrownBy(() -> RetentionWindow.parse("P0D"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
