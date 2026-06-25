package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Self-scoped read aggregations over a single user's own {@code query_requests} (joined with
 * {@code ai_analyses}) — the data backing the personalized dashboard (AF-498). Every method is scoped
 * to {@code (organizationId, userId)} where {@code userId = query_requests.submitted_by}, so a caller
 * can only ever see their own queries. The org-scoped analogue for admins is
 * {@link AiAnalysisStatsLookupService}.
 */
public interface MyQueryInsightsLookupService {

    /**
     * Day-bucketed status- and risk-level trend series for the user's queries submitted in
     * {@code [from, to)}, ordered by date ascending.
     *
     * @param organizationId required; scopes through {@code datasources.organization_id}.
     * @param userId         required; {@code query_requests.submitted_by}.
     * @param from           inclusive lower bound on {@code query_requests.created_at}.
     * @param to             exclusive upper bound on {@code query_requests.created_at}.
     */
    MyQueryTrendsRaw trends(UUID organizationId, UUID userId, Instant from, Instant to);

    /** Count of the user's queries grouped by status (all statuses present), for the summary widget. */
    List<MyQueryStatusCount> statusCounts(UUID organizationId, UUID userId);

    /**
     * The user's most recent non-failed AI analyses that carry at least one optimization suggestion,
     * newest first, capped at {@code limit}. The dashboard parses {@code optimizationsJson} into the
     * actionable backlog and joins it against per-item dismissal state.
     */
    List<MyOptimizationSourceView> recentOptimizationSources(UUID organizationId, UUID userId, int limit);
}
