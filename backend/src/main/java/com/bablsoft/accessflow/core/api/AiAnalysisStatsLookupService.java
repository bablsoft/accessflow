package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregates {@code ai_analyses} rows (joined with {@code query_requests}) for the admin
 * dashboard. Org-scoped: every query is filtered through
 * {@code datasources.organization_id}, so a caller can never see another organization's data.
 */
public interface AiAnalysisStatsLookupService {

    /**
     * @param organizationId required. Scopes every series to datasources owned by this org.
     * @param from           inclusive lower bound on {@code ai_analyses.created_at}.
     * @param to             exclusive upper bound on {@code ai_analyses.created_at}.
     * @param datasourceId   optional filter; when non-null restricts to a single datasource.
     */
    AiAnalysisStatsRaw query(UUID organizationId, Instant from, Instant to, UUID datasourceId);

    /**
     * Sums {@code prompt_tokens + completion_tokens} across the organization's {@code ai_analyses}
     * rows created on or after {@code since} — used by the AI rate-limiter to enforce a monthly
     * token budget (AF-55). Org-scoped through {@code datasources.organization_id}; returns 0 when
     * there are no matching rows.
     *
     * @param organizationId required. Scopes the sum to datasources owned by this org.
     * @param since          inclusive lower bound on {@code ai_analyses.created_at}.
     */
    long sumTokensSince(UUID organizationId, Instant since);
}
