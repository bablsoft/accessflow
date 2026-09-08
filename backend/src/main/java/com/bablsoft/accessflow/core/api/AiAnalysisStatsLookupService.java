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

    /**
     * Sums the prompt + completion tokens the organization's in-app help chat spent on or after
     * {@code since} (AF-903, epic AF-899 decision 10).
     *
     * <p>Separate from {@link #sumTokensSince} because help turns deliberately do <b>not</b> write
     * {@code ai_analyses} rows — that table backs the admin AI-analyses history page, and filling it
     * with chat turns would wreck it. But the tokens are spent against the same provider key, so the
     * rate limiter adds both sums before comparing against
     * {@code ACCESSFLOW_AI_RATE_LIMIT_TOKENS_PER_MONTH}; without this a chatty help agent would drain
     * the budget invisibly and never trip it.
     *
     * @param organizationId required. Scopes the sum to this organization's help conversations.
     * @param since          inclusive lower bound on when the turn was recorded.
     */
    long sumHelpChatTokensSince(UUID organizationId, Instant since);
}
