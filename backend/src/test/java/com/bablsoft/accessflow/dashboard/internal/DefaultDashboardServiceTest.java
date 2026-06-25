package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.ai.api.AnomalyBadgeView;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.core.api.MyQueryInsightsLookupService;
import com.bablsoft.accessflow.core.api.MyQueryStatusCount;
import com.bablsoft.accessflow.core.api.MyQueryTrendsRaw;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionService;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewService.PendingReview;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final ReviewService reviewService = mock(ReviewService.class);
    private final QueryRequestLookupService queryLookup = mock(QueryRequestLookupService.class);
    private final MyQueryInsightsLookupService insights = mock(MyQueryInsightsLookupService.class);
    private final BehaviorAnomalyLookupService anomalyLookup = mock(BehaviorAnomalyLookupService.class);
    private final DashboardSuggestionService suggestionService = mock(DashboardSuggestionService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DefaultDashboardService service = new DefaultDashboardService(
            reviewService, queryLookup, insights, anomalyLookup, suggestionService, clock);

    private QueryListItemView query() {
        return new QueryListItemView(UUID.randomUUID(), UUID.randomUUID(), "DB", USER,
                "u@x.io", "User", QueryType.SELECT, QueryStatus.PENDING_REVIEW, RiskLevel.LOW,
                10, false, null, NOW);
    }

    private PendingReview pending() {
        return new PendingReview(UUID.randomUUID(), UUID.randomUUID(), "DB", UUID.randomUUID(),
                "s@x.io", "SELECT 1", QueryType.SELECT, "why", UUID.randomUUID(), RiskLevel.HIGH,
                80, "summary", 1, NOW);
    }

    @Test
    void summaryAggregatesCountsAndOpenStatuses() {
        when(reviewService.listPendingForReviewer(any(), any()))
                .thenReturn(new PageResponse<>(List.of(pending()), 0, 5, 3, 1));
        when(insights.statusCounts(ORG, USER)).thenReturn(List.of(
                new MyQueryStatusCount(QueryStatus.PENDING_AI, 2),
                new MyQueryStatusCount(QueryStatus.PENDING_REVIEW, 1),
                new MyQueryStatusCount(QueryStatus.APPROVED, 4),
                new MyQueryStatusCount(QueryStatus.EXECUTED, 9))); // terminal — not open
        when(queryLookup.findForOrganization(any(QueryListFilter.class), any()))
                .thenReturn(new PageResponse<>(List.of(query()), 0, 5, 1, 1));
        when(anomalyLookup.badgeForUser(ORG, USER)).thenReturn(new AnomalyBadgeView(2, 7.0));
        when(suggestionService.countOpen(ORG, USER)).thenReturn(5L);

        var summary = service.summary(ORG, USER, UserRoleType.REVIEWER);

        assertThat(summary.pendingApprovalsCount()).isEqualTo(3);
        assertThat(summary.openQueriesCount()).isEqualTo(7); // 2 + 1 + 4, EXECUTED excluded
        assertThat(summary.openAnomaliesCount()).isEqualTo(2);
        assertThat(summary.openSuggestionsCount()).isEqualTo(5);
        assertThat(summary.recentQueries()).hasSize(1);
        assertThat(summary.recentPendingApprovals()).hasSize(1);
    }

    @Test
    void summaryScopesQueryFilterToCurrentUser() {
        when(reviewService.listPendingForReviewer(any(), any()))
                .thenReturn(PageResponse.empty(0, 5));
        when(insights.statusCounts(ORG, USER)).thenReturn(List.of());
        when(queryLookup.findForOrganization(any(QueryListFilter.class), any()))
                .thenReturn(PageResponse.empty(0, 5));
        when(anomalyLookup.badgeForUser(ORG, USER)).thenReturn(AnomalyBadgeView.none());
        when(suggestionService.countOpen(ORG, USER)).thenReturn(0L);

        service.summary(ORG, USER, UserRoleType.ANALYST);

        var captor = ArgumentCaptor.forClass(QueryListFilter.class);
        verify(queryLookup).findForOrganization(captor.capture(), any());
        assertThat(captor.getValue().submittedByUserId()).isEqualTo(USER);
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG);
    }

    @Test
    void summaryRejectsNullArgs() {
        assertThatThrownBy(() -> service.summary(null, USER, UserRoleType.ANALYST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(ORG, null, UserRoleType.ANALYST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trendsAppliesDefaultWindowWhenBoundsNull() {
        when(insights.trends(eq(ORG), eq(USER), any(), any()))
                .thenReturn(new MyQueryTrendsRaw(List.of(), List.of()));

        service.trends(ORG, USER, null, null);

        var from = ArgumentCaptor.forClass(Instant.class);
        var to = ArgumentCaptor.forClass(Instant.class);
        verify(insights).trends(eq(ORG), eq(USER), from.capture(), to.capture());
        assertThat(to.getValue()).isEqualTo(NOW);
        assertThat(from.getValue()).isEqualTo(NOW.minus(java.time.Duration.ofDays(30)));
    }

    @Test
    void trendsPassesExplicitBounds() {
        var from = NOW.minus(java.time.Duration.ofDays(7));
        when(insights.trends(ORG, USER, from, NOW)).thenReturn(new MyQueryTrendsRaw(List.of(), List.of()));

        service.trends(ORG, USER, from, NOW);

        verify(insights).trends(ORG, USER, from, NOW);
    }
}
