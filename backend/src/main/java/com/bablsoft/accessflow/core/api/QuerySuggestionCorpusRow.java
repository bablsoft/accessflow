package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One approved historical query as the suggestion aggregation (#776) reads it — the minimum needed
 * to canonicalise the SQL, parse its referenced tables for the engine, and tally frequency,
 * recency and per-submitter breadth.
 *
 * <p>{@code submittedAt} is the request's {@code created_at}, not an approval timestamp:
 * {@code query_requests} has no approved-at column. {@code updated_at} is the optimistic-locking
 * {@code @Version} field — on an {@code EXECUTED} row it holds the execution time, not the
 * approval — and {@code execution_completed_at} is null for a query that was approved but never
 * run. Submission time is present on every row in the corpus and orders it monotonically, which is
 * all the recency term needs.
 */
public record QuerySuggestionCorpusRow(
        UUID datasourceId,
        DbType dbType,
        String sqlText,
        QueryType queryType,
        UUID submittedByUserId,
        Instant submittedAt) {
}
