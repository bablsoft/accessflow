package com.bablsoft.accessflow.sqlreview.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted SQL review findings (#864) — the record of what {@link SqlReviewService} concluded for a
 * query at the moment it was submitted, and for each query member of a request group at the moment
 * the group was submitted.
 *
 * <p>Findings are written once, at submission, so they exist even when AI analysis is skipped or
 * fails. The workflow reads {@link #blockingRuleIds} at its decision point; the reviewer surfaces
 * (query detail, reviewer queue, break-glass retro-review, request-group detail) read the rest.
 * Rows carry {@code rule_id} + {@code args} only — render through {@link SqlReviewFindingRenderer}.
 */
public interface SqlReviewFindingService {

    /**
     * Replace the findings recorded for a query with the given result. A not-applicable or clean
     * result leaves no rows behind.
     */
    void recordForQuery(UUID queryRequestId, SqlReviewResult result);

    /** Same as {@link #recordForQuery} for one query member of a request group (AF-501). */
    void recordForGroupItem(UUID requestGroupItemId, SqlReviewResult result);

    /** Findings for one query, ordered statement → line; empty when none were recorded. */
    List<SqlReviewFinding> findByQueryRequest(UUID queryRequestId);

    /** Findings keyed by query id; a query without findings is absent from the map. */
    Map<UUID, List<SqlReviewFinding>> findByQueryRequests(Collection<UUID> queryRequestIds);

    /** Findings keyed by request-group item id; an item without findings is absent from the map. */
    Map<UUID, List<SqlReviewFinding>> findByGroupItems(Collection<UUID> requestGroupItemIds);

    /** Distinct ids of the rules that fired at {@code BLOCK} for a query, sorted; empty when none. */
    List<String> blockingRuleIds(UUID queryRequestId);

    /** Count of {@code BLOCK} findings per query; a query with none is absent from the map. */
    Map<UUID, Integer> countBlockingByQueryRequests(Collection<UUID> queryRequestIds);
}
