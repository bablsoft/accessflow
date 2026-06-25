package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.core.api.MyQueryStatusCount;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.dashboard.api.DashboardRiskCount;
import com.bablsoft.accessflow.dashboard.api.DashboardWeeklySummary;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardSummaryWritersTest {

    private final DashboardSummaryCsvWriter csv = new DashboardSummaryCsvWriter();
    private final DashboardSummaryPdfWriter pdf = new DashboardSummaryPdfWriter();

    private DashboardWeeklySummary summary() {
        return new DashboardWeeklySummary(UUID.randomUUID(), UUID.randomUUID(),
                "user, with comma@x.io", "User \"Q\"",
                LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 29), 5,
                List.of(new MyQueryStatusCount(QueryStatus.EXECUTED, 3),
                        new MyQueryStatusCount(QueryStatus.PENDING_REVIEW, 2)),
                List.of(new DashboardRiskCount(RiskLevel.LOW, 4),
                        new DashboardRiskCount(RiskLevel.HIGH, 1)),
                2, 1, 3, Instant.parse("2026-06-25T12:00:00Z"));
    }

    @Test
    void csvContainsMetricsAndBreakdownsAndEscapes() {
        var out = new String(csv.write(summary()), StandardCharsets.UTF_8);
        assertThat(out).contains("section,key,value");
        assertThat(out).contains("metrics,total_queries,5");
        assertThat(out).contains("status_breakdown,EXECUTED,3");
        assertThat(out).contains("risk_breakdown,HIGH,1");
        // comma in the email is quoted; embedded quote in display name is doubled
        assertThat(out).contains("\"user, with comma@x.io\"");
        assertThat(out).contains("\"User \"\"Q\"\"\"");
    }

    @Test
    void csvHandlesEmptyBreakdowns() {
        var empty = new DashboardWeeklySummary(UUID.randomUUID(), UUID.randomUUID(), null, null,
                LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 29), 0,
                List.of(), List.of(), 0, 0, 0, Instant.parse("2026-06-25T12:00:00Z"));
        var out = new String(csv.write(empty), StandardCharsets.UTF_8);
        assertThat(out).contains("metrics,total_queries,0");
    }

    @Test
    void pdfRendersValidDocument() {
        byte[] bytes = pdf.write(summary());
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void pdfRendersEmptySummary() {
        var empty = new DashboardWeeklySummary(UUID.randomUUID(), UUID.randomUUID(), null, null,
                LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 29), 0,
                List.of(), List.of(), 0, 0, 0, Instant.parse("2026-06-25T12:00:00Z"));
        assertThat(pdf.write(empty)).isNotEmpty();
    }
}
