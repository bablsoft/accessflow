package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryDryRunResult;

import java.util.UUID;

/**
 * Produces a non-committing dry-run of a query (issue AF-445): the engine's execution plan and a
 * best-effort estimated row impact, <em>without</em> executing or mutating data and without creating
 * a {@code query_request}.
 *
 * <p>Like the {@link SampleDataService} sample path, this is a review-bypassing read that still
 * applies full governance: the caller must have access to the datasource (org + permission row), the
 * referenced tables must be inside their allow-list with the matching capability, and the caller's
 * row-security predicates are injected so the plan reflects the governed query. Engines with no plan
 * concept return a result with {@code supported=false} carrying a localized reason.
 */
public interface QueryDryRunService {

    QueryDryRunResult dryRun(UUID datasourceId, String sql, UUID userId, UUID organizationId,
                             boolean isAdmin);
}
