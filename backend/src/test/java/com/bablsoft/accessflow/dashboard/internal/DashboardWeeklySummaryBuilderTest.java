package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.ai.api.AnomalyBadgeView;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.MyQueryInsightsLookupService;
import com.bablsoft.accessflow.core.api.MyQueryRiskBucket;
import com.bablsoft.accessflow.core.api.MyQueryStatusBucket;
import com.bablsoft.accessflow.core.api.MyQueryTrendsRaw;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionService;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardWeeklySummaryBuilderTest {

    private static final Instant NOW = Instant.parse("2026-06-25T12:00:00Z"); // Thursday
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final UserQueryService userQueryService = mock(UserQueryService.class);
    private final ReviewService reviewService = mock(ReviewService.class);
    private final MyQueryInsightsLookupService insights = mock(MyQueryInsightsLookupService.class);
    private final BehaviorAnomalyLookupService anomalyLookup = mock(BehaviorAnomalyLookupService.class);
    private final DashboardSuggestionService suggestionService = mock(DashboardSuggestionService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DashboardWeeklySummaryBuilder builder = new DashboardWeeklySummaryBuilder(
            userQueryService, reviewService, insights, anomalyLookup, suggestionService, clock);

    private UserView user(UserRoleType role) {
        return new UserView(USER, "u@x.io", "User Name", role, ORG, true,
                AuthProviderType.LOCAL, "h", null, "en", false, NOW);
    }

    @Test
    void buildAggregatesTrendsAndCountsForCurrentWeek() {
        when(userQueryService.findById(USER)).thenReturn(Optional.of(user(UserRoleType.REVIEWER)));
        var d = LocalDate.of(2026, 6, 23);
        when(insights.trends(any(), any(), any(), any())).thenReturn(new MyQueryTrendsRaw(
                List.of(new MyQueryStatusBucket(d, QueryStatus.EXECUTED, 3),
                        new MyQueryStatusBucket(d, QueryStatus.PENDING_REVIEW, 2)),
                List.of(new MyQueryRiskBucket(d, RiskLevel.LOW, 4),
                        new MyQueryRiskBucket(d, RiskLevel.HIGH, 1))));
        when(reviewService.listPendingForReviewer(any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 6, 0));
        when(anomalyLookup.badgeForUser(ORG, USER)).thenReturn(new AnomalyBadgeView(1, 5.0));
        when(suggestionService.countOpen(ORG, USER)).thenReturn(2L);

        var summary = builder.build(ORG, USER, null);

        assertThat(summary.userEmail()).isEqualTo("u@x.io");
        assertThat(summary.weekStart()).isEqualTo(LocalDate.of(2026, 6, 22)); // Monday
        assertThat(summary.weekEnd()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(summary.totalQueries()).isEqualTo(5);
        assertThat(summary.statusBreakdown()).hasSize(2);
        assertThat(summary.riskBreakdown()).hasSize(2);
        assertThat(summary.pendingApprovals()).isEqualTo(6);
        assertThat(summary.openAnomalies()).isEqualTo(1);
        assertThat(summary.openSuggestions()).isEqualTo(2);
        assertThat(summary.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void buildUsesExplicitWeek() {
        when(userQueryService.findById(USER)).thenReturn(Optional.of(user(UserRoleType.ANALYST)));
        when(insights.trends(any(), any(), any(), any()))
                .thenReturn(new MyQueryTrendsRaw(List.of(), List.of()));
        when(reviewService.listPendingForReviewer(any(), any())).thenReturn(PageResponse.empty(0, 1));
        when(anomalyLookup.badgeForUser(ORG, USER)).thenReturn(AnomalyBadgeView.none());
        when(suggestionService.countOpen(ORG, USER)).thenReturn(0L);

        var summary = builder.build(ORG, USER, LocalDate.of(2026, 1, 7)); // Wed in week of Jan 5

        assertThat(summary.weekStart()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(summary.weekEnd()).isEqualTo(LocalDate.of(2026, 1, 12));
        assertThat(summary.pendingApprovals()).isZero();
    }

    @Test
    void buildToleratesMissingUser() {
        when(userQueryService.findById(USER)).thenReturn(Optional.empty());
        when(insights.trends(any(), any(), any(), any()))
                .thenReturn(new MyQueryTrendsRaw(List.of(), List.of()));
        when(anomalyLookup.badgeForUser(ORG, USER)).thenReturn(AnomalyBadgeView.none());
        when(suggestionService.countOpen(ORG, USER)).thenReturn(0L);

        var summary = builder.build(ORG, USER, null);

        assertThat(summary.userEmail()).isNull();
        assertThat(summary.pendingApprovals()).isZero();
    }
}
