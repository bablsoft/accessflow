package com.bablsoft.accessflow.dashboard.api;

import com.bablsoft.accessflow.core.api.MyQueryStatusCount;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The report model for a user's weekly dashboard summary (AF-498) — the input to both the signed
 * on-demand export and the scheduled email digest. Covers the ISO week {@code [weekStart, weekEnd)}.
 * Counts under {@code statusBreakdown}/{@code riskBreakdown}/{@code totalQueries} are scoped to
 * queries submitted within the week; {@code pendingApprovals}/{@code openAnomalies}/
 * {@code openSuggestions} are current (point-in-time) backlog figures.
 */
public record DashboardWeeklySummary(
        UUID organizationId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        LocalDate weekStart,
        LocalDate weekEnd,
        long totalQueries,
        List<MyQueryStatusCount> statusBreakdown,
        List<DashboardRiskCount> riskBreakdown,
        long pendingApprovals,
        long openAnomalies,
        long openSuggestions,
        Instant generatedAt) {

    public DashboardWeeklySummary {
        statusBreakdown = statusBreakdown == null ? List.of() : List.copyOf(statusBreakdown);
        riskBreakdown = riskBreakdown == null ? List.of() : List.copyOf(riskBreakdown);
    }
}
