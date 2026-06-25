package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.core.api.MyQueryInsightsLookupService;
import com.bablsoft.accessflow.core.api.MyQueryStatusBucket;
import com.bablsoft.accessflow.core.api.MyQueryStatusCount;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.dashboard.api.DashboardRiskCount;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionService;
import com.bablsoft.accessflow.dashboard.api.DashboardWeeklySummary;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewService.ReviewerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/**
 * Builds a user's {@link DashboardWeeklySummary} report (AF-498) for the ISO week containing a given
 * date — shared by the on-demand signed export and the scheduled email digest. Composes the same
 * self-scoped {@code api} services as {@code DefaultDashboardService}.
 */
@Service
@RequiredArgsConstructor
class DashboardWeeklySummaryBuilder {

    private final UserQueryService userQueryService;
    private final ReviewService reviewService;
    private final MyQueryInsightsLookupService insightsLookupService;
    private final BehaviorAnomalyLookupService anomalyLookupService;
    private final DashboardSuggestionService suggestionService;
    private final Clock clock;

    @Transactional(readOnly = true)
    DashboardWeeklySummary build(UUID organizationId, UUID userId, LocalDate week) {
        LocalDate anchor = week != null ? week : LocalDate.now(clock);
        LocalDate weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusWeeks(1);
        Instant from = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = weekEnd.atStartOfDay(ZoneOffset.UTC).toInstant();

        UserView user = userQueryService.findById(userId).orElse(null);
        UserRoleType role = user != null ? user.role() : null;

        var trends = insightsLookupService.trends(organizationId, userId, from, to);

        var statusTotals = new EnumMap<QueryStatus, Long>(QueryStatus.class);
        for (MyQueryStatusBucket b : trends.statusByDay()) {
            statusTotals.merge(b.status(), b.count(), Long::sum);
        }
        List<MyQueryStatusCount> statusBreakdown = statusTotals.entrySet().stream()
                .map(e -> new MyQueryStatusCount(e.getKey(), e.getValue()))
                .toList();
        long totalQueries = statusTotals.values().stream().mapToLong(Long::longValue).sum();

        var riskTotals = new EnumMap<RiskLevel, Long>(RiskLevel.class);
        trends.riskByDay().forEach(b -> riskTotals.merge(b.riskLevel(), b.count(), Long::sum));
        List<DashboardRiskCount> riskBreakdown = riskTotals.entrySet().stream()
                .map(e -> new DashboardRiskCount(e.getKey(), e.getValue()))
                .toList();

        long pendingApprovals = role == null ? 0L : reviewService.listPendingForReviewer(
                new ReviewerContext(userId, organizationId, role), PageRequest.of(0, 1)).totalElements();
        long openAnomalies = anomalyLookupService.badgeForUser(organizationId, userId).openCount();
        long openSuggestions = suggestionService.countOpen(organizationId, userId);

        return new DashboardWeeklySummary(
                organizationId,
                userId,
                user != null ? user.email() : null,
                user != null ? user.displayName() : null,
                weekStart,
                weekEnd,
                totalQueries,
                statusBreakdown,
                riskBreakdown,
                pendingApprovals,
                openAnomalies,
                openSuggestions,
                clock.instant());
    }
}
