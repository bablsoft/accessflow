package com.bablsoft.accessflow.dashboard.api;

import com.bablsoft.accessflow.apigov.api.ApiRequestView;
import com.bablsoft.accessflow.apigov.api.ApiReviewService.PendingApiReview;
import com.bablsoft.accessflow.core.api.MyQueryStatusCount;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.workflow.api.ReviewService.PendingReview;

import java.util.List;

/**
 * Single self-scoped aggregate for the personalized dashboard home (AF-498): the headline
 * counts plus short recent lists, all scoped to the current user. Composed from
 * {@code ReviewService}, {@code MyQueryInsightsLookupService}, the query read path,
 * {@code BehaviorAnomalyLookupService}, the dashboard suggestion backlog, and — for API Access
 * Governance (AF-500) — {@code MyApiRequestInsightsLookupService}, {@code ApiRequestService}, and
 * {@code ApiReviewService}.
 */
public record DashboardSummary(
        long pendingApprovalsCount,
        long openQueriesCount,
        long openAnomaliesCount,
        long openSuggestionsCount,
        long openApiRequestsCount,
        long pendingApiApprovalsCount,
        List<MyQueryStatusCount> statusCounts,
        List<QueryListItemView> recentQueries,
        List<PendingReview> recentPendingApprovals,
        List<ApiRequestView> recentApiRequests,
        List<PendingApiReview> recentPendingApiApprovals) {

    public DashboardSummary {
        statusCounts = statusCounts == null ? List.of() : List.copyOf(statusCounts);
        recentQueries = recentQueries == null ? List.of() : List.copyOf(recentQueries);
        recentPendingApprovals = recentPendingApprovals == null
                ? List.of() : List.copyOf(recentPendingApprovals);
        recentApiRequests = recentApiRequests == null ? List.of() : List.copyOf(recentApiRequests);
        recentPendingApiApprovals = recentPendingApiApprovals == null
                ? List.of() : List.copyOf(recentPendingApiApprovals);
    }
}
