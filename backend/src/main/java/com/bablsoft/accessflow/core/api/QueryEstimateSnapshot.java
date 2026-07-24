package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module DTO carrying a persisted pre-flight cost / blast-radius estimate (issue AF-624) —
 * the {@code query_estimates} row computed asynchronously after submission from the engine's
 * non-committing dry-run plus, for UPDATE/DELETE, a governed affected-row count. {@code supported}
 * is {@code false} when the engine has no plan concept; {@code failed} marks an unexpected
 * computation error. {@code estimatedRows}, {@code affectedRowCount}, {@code scanType} and
 * {@code estimatedCost} are individually nullable — best-effort signals filled from what the
 * engine's plan exposes.
 */
public record QueryEstimateSnapshot(
        UUID id,
        UUID queryRequestId,
        String engineId,
        QueryType queryType,
        boolean supported,
        Long estimatedRows,
        Long affectedRowCount,
        String scanType,
        Double estimatedCost,
        String planJson,
        String rawPlan,
        String unsupportedReason,
        boolean failed,
        String errorMessage,
        Integer durationMs,
        Instant createdAt) {
}
