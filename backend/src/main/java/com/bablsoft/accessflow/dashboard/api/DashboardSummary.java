package com.bablsoft.accessflow.dashboard.api;

import com.bablsoft.accessflow.core.api.MyQueryStatusCount;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.workflow.api.ReviewService.PendingReview;

import java.util.List;

/**
 * Single self-scoped aggregate for the personalized dashboard home (AF-498): the four headline
 * counts plus short recent lists, all scoped to the current user. Composed from
 * {@code ReviewService}, {@code MyQueryInsightsLookupService}, the query read path,
 * {@code BehaviorAnomalyLookupService}, and the dashboard suggestion backlog.
 */
public record DashboardSummary(
        long pendingApprovalsCount,
        long openQueriesCount,
        long openAnomaliesCount,
        long openSuggestionsCount,
        List<MyQueryStatusCount> statusCounts,
        List<QueryListItemView> recentQueries,
        List<PendingReview> recentPendingApprovals) {

    public DashboardSummary {
        statusCounts = statusCounts == null ? List.of() : List.copyOf(statusCounts);
        recentQueries = recentQueries == null ? List.of() : List.copyOf(recentQueries);
        recentPendingApprovals = recentPendingApprovals == null
                ? List.of() : List.copyOf(recentPendingApprovals);
    }
}
