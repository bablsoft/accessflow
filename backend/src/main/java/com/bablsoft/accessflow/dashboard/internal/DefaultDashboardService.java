package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.apigov.api.ApiRequestListFilter;
import com.bablsoft.accessflow.apigov.api.ApiRequestService;
import com.bablsoft.accessflow.apigov.api.ApiRequestView;
import com.bablsoft.accessflow.apigov.api.ApiReviewService;
import com.bablsoft.accessflow.apigov.api.ApiReviewService.PendingApiReview;
import com.bablsoft.accessflow.apigov.api.ApiReviewService.PendingApiReviewFilter;
import com.bablsoft.accessflow.apigov.api.MyApiRequestInsightsLookupService;
import com.bablsoft.accessflow.apigov.api.MyApiRequestStatusCount;
import com.bablsoft.accessflow.apigov.api.MyApiRequestTrendsRaw;
import com.bablsoft.accessflow.core.api.MyQueryInsightsLookupService;
import com.bablsoft.accessflow.core.api.MyQueryStatusCount;
import com.bablsoft.accessflow.core.api.MyQueryTrendsRaw;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.dashboard.api.DashboardService;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionService;
import com.bablsoft.accessflow.dashboard.api.DashboardSummary;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService.PendingDeploymentReview;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService.PendingDeploymentReviewFilter;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewService.PendingReview;
import com.bablsoft.accessflow.workflow.api.ReviewService.ReviewerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Composes the self-scoped personalized dashboard (AF-498) from existing module {@code api} services:
 * reviewer queue ({@code ReviewService}), the user's queries + status/risk insights
 * ({@code QueryRequestLookupService} / {@code MyQueryInsightsLookupService}), open anomalies
 * ({@code BehaviorAnomalyLookupService}), the AI suggestion backlog, and — for deployment approval
 * governance (#926) — the caller's own deployment requests ({@code DeploymentRequestService}) plus
 * their deployment review queue ({@code DeploymentReviewService}). No cross-module
 * {@code internal} access; read-only.
 */
@Service
@RequiredArgsConstructor
class DefaultDashboardService implements DashboardService {

    /** Non-terminal statuses that count as a user's "open" (in-flight) queries. */
    private static final Set<QueryStatus> OPEN_STATUSES =
            Set.of(QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW, QueryStatus.APPROVED);

    private static final int RECENT_LIMIT = 5;
    private static final Duration DEFAULT_TREND_WINDOW = Duration.ofDays(30);

    private final ReviewService reviewService;
    private final QueryRequestLookupService queryRequestLookupService;
    private final MyQueryInsightsLookupService insightsLookupService;
    private final BehaviorAnomalyLookupService anomalyLookupService;
    private final DashboardSuggestionService suggestionService;
    private final MyApiRequestInsightsLookupService apiRequestInsightsLookupService;
    private final ApiRequestService apiRequestService;
    private final ApiReviewService apiReviewService;
    private final DeploymentRequestService deploymentRequestService;
    private final DeploymentReviewService deploymentReviewService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public DashboardSummary summary(UUID organizationId, UUID userId, String roleName,
                                    Set<Permission> permissions) {
        if (organizationId == null || userId == null) {
            throw new IllegalArgumentException("organizationId and userId are required");
        }
        var pending = reviewService.listPendingForReviewer(
                new ReviewerContext(userId, organizationId, roleName, permissions),
                PageRequest.of(0, RECENT_LIMIT));
        List<PendingReview> recentPending = pending.content();

        List<MyQueryStatusCount> statusCounts = insightsLookupService.statusCounts(organizationId, userId);
        long openQueries = statusCounts.stream()
                .filter(c -> OPEN_STATUSES.contains(c.status()))
                .mapToLong(MyQueryStatusCount::count)
                .sum();

        List<QueryListItemView> recentQueries = queryRequestLookupService.findForOrganization(
                new QueryListFilter(organizationId, userId, null, null, null, null, null),
                PageRequest.of(0, RECENT_LIMIT)).content();

        long openAnomalies = anomalyLookupService.badgeForUser(organizationId, userId).openCount();
        long openSuggestions = suggestionService.countOpen(organizationId, userId);

        List<MyApiRequestStatusCount> apiStatusCounts =
                apiRequestInsightsLookupService.statusCounts(organizationId, userId);
        long openApiRequests = apiStatusCounts.stream()
                .filter(c -> OPEN_STATUSES.contains(c.status()))
                .mapToLong(MyApiRequestStatusCount::count)
                .sum();

        List<ApiRequestView> recentApiRequests = apiRequestService.list(
                new ApiRequestListFilter(organizationId, userId, null, null, null, null, null, null, null),
                PageRequest.of(0, RECENT_LIMIT)).content();

        var pendingApi = apiReviewService.listPending(
                new ApiReviewService.ReviewerContext(userId, organizationId, roleName, permissions),
                new PendingApiReviewFilter(null, null), PageRequest.of(0, RECENT_LIMIT));
        List<PendingApiReview> recentPendingApi = pendingApi.content();

        // Always self-scoped: the dashboard shows the caller's own deployments, never the
        // organization-wide feed a DEPLOYMENT_REVIEW holder could also list.
        List<DeploymentRequestView> recentDeployments = deploymentRequestService.list(
                new DeploymentRequestListFilter(organizationId, userId, null, null, null, null,
                        null, null),
                PageRequest.of(0, RECENT_LIMIT)).content();
        long openDeployments = OPEN_STATUSES.stream()
                .mapToLong(status -> deploymentRequestService.list(
                        new DeploymentRequestListFilter(organizationId, userId, null, null, null,
                                status, null, null),
                        PageRequest.of(0, 1)).totalElements())
                .sum();

        var pendingDeployments = deploymentReviewService.listPending(
                new DeploymentReviewService.ReviewerContext(userId, organizationId, roleName, permissions),
                new PendingDeploymentReviewFilter(null), PageRequest.of(0, RECENT_LIMIT));
        List<PendingDeploymentReview> recentPendingDeployments = pendingDeployments.content();

        return new DashboardSummary(
                pending.totalElements(),
                openQueries,
                openAnomalies,
                openSuggestions,
                openApiRequests,
                pendingApi.totalElements(),
                openDeployments,
                pendingDeployments.totalElements(),
                statusCounts,
                recentQueries,
                recentPending,
                recentApiRequests,
                recentPendingApi,
                recentDeployments,
                recentPendingDeployments);
    }

    @Override
    @Transactional(readOnly = true)
    public MyQueryTrendsRaw trends(UUID organizationId, UUID userId, Instant from, Instant to) {
        Instant resolvedTo = to != null ? to : clock.instant();
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_TREND_WINDOW);
        return insightsLookupService.trends(organizationId, userId, resolvedFrom, resolvedTo);
    }

    @Override
    @Transactional(readOnly = true)
    public MyApiRequestTrendsRaw apiRequestTrends(UUID organizationId, UUID userId, Instant from, Instant to) {
        Instant resolvedTo = to != null ? to : clock.instant();
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_TREND_WINDOW);
        return apiRequestInsightsLookupService.trends(organizationId, userId, resolvedFrom, resolvedTo);
    }
}
