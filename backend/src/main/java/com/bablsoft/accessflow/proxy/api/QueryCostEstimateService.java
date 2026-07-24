package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryEstimateSnapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Computes and persists the pre-flight cost / blast-radius estimate for a submitted query (issue
 * AF-624): the engine's non-committing dry-run plan (estimated rows, scan type, cost) plus, for
 * UPDATE/DELETE, a governed affected-row count. Runs asynchronously right after submission and is
 * consumed by reviewers ({@code GET /queries/{id}}), the routing-policy engine
 * ({@code estimated_rows} / {@code scan_type} conditions), and the AI analyzer's prompt context.
 */
public interface QueryCostEstimateService {

    /**
     * Idempotent: returns the existing estimate when one is already persisted for the query,
     * otherwise computes, persists, and publishes {@code QueryEstimateCompletedEvent} (or
     * {@code QueryEstimateFailedEvent} plus a sentinel row on unexpected errors). Never throws —
     * failures, timeouts, and unsupported engines persist a degraded row instead of blocking the
     * caller. Empty only when the query request itself does not exist.
     */
    Optional<QueryEstimateSnapshot> estimateSubmittedQuery(UUID queryRequestId);
}
