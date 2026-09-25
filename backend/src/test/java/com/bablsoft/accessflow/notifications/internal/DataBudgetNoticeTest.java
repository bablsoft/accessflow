package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataBudgetNoticeTest {

    private static DataBudgetNotice notice(Long maxRows, Long maxBytes, int windowMinutes) {
        return new DataBudgetNotice(UUID.randomUUID(), "b", false, 80, 82, maxRows, maxBytes, 820L,
                1_500_000L, windowMinutes, DataBudgetBreachAction.REJECT);
    }

    @Test
    void usageLinesOnlyForTheLimitsTheBudgetSets() {
        var rowsOnly = notice(1000L, null, 60);
        assertThat(rowsOnly.rowsUsage()).isEqualTo("820 / 1000 rows");
        assertThat(rowsOnly.bytesUsage()).isNull();

        var bytesOnly = notice(null, 5_000_000L, 60);
        assertThat(bytesOnly.rowsUsage()).isNull();
        assertThat(bytesOnly.bytesUsage()).isEqualTo("1.5 MB (1500000 B) / 5 MB (5000000 B)");
    }

    @ParameterizedTest
    @CsvSource({
            "1440, DAYS, 1, 1 day",
            "10080, DAYS, 7, 7 days",
            "60, HOURS, 1, 1 hour",
            "180, HOURS, 3, 3 hours",
            "90, MINUTES, 90, 90 minutes",
            "1, MINUTES, 1, 1 minute",
            "0, MINUTES, 0, 0 minutes"})
    void windowRendersInTheLargestWholeUnit(int minutes, String unit, int value, String label) {
        var n = notice(1L, null, minutes);
        assertThat(n.windowUnit()).isEqualTo(unit);
        assertThat(n.windowValue()).isEqualTo(value);
        assertThat(n.windowLabel()).isEqualTo(label);
    }
}
