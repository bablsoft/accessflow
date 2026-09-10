package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Rebuilds the precomputed {@code query_suggestions} read model (#776) from the approved query
 * history of every organisation.
 *
 * <p>Driven by {@code QuerySuggestionAggregationJob}. Deterministic: a redundant pass over unchanged
 * history rewrites each row with the values it already held, so a multi-replica cluster running the
 * timer more than once per period costs load, not correctness.
 */
public interface QuerySuggestionAggregationService {

    /**
     * Aggregates every organisation, one transaction and one failure boundary per datasource.
     * No-ops when the feature is disabled.
     */
    void aggregateAll();

    /**
     * Rebuilds a single datasource's suggestions now — the on-demand path behind the admin
     * "recompute" endpoint, so an operator who has just imported history need not wait out a poll
     * interval. Runs on the calling thread; the caller owns the cluster-wide lock and the handoff.
     *
     * @return {@code false} when the feature is disabled and nothing was recomputed.
     */
    boolean aggregateDatasource(UUID organizationId, UUID datasourceId);
}
