package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Self-scoped read aggregations over a single user's own {@code api_requests} (joined with
 * {@code ai_analyses}) — the data backing the API-request widgets of the personalized dashboard
 * (AF-498). Every method is scoped to {@code (organizationId, userId)} where
 * {@code userId = api_requests.submitted_by}, so a caller can only ever see their own API requests.
 * The SQL-query analogue is {@code core.api.MyQueryInsightsLookupService}.
 */
public interface MyApiRequestInsightsLookupService {

    /**
     * Day-bucketed status- and risk-level trend series for the user's API requests submitted in
     * {@code [from, to)}, ordered by date ascending.
     *
     * @param organizationId required; {@code api_requests.organization_id}.
     * @param userId         required; {@code api_requests.submitted_by}.
     * @param from           inclusive lower bound on {@code api_requests.created_at}.
     * @param to             exclusive upper bound on {@code api_requests.created_at}.
     */
    MyApiRequestTrendsRaw trends(UUID organizationId, UUID userId, Instant from, Instant to);

    /** Count of the user's API requests grouped by status (all statuses present), for the summary widget. */
    List<MyApiRequestStatusCount> statusCounts(UUID organizationId, UUID userId);
}
