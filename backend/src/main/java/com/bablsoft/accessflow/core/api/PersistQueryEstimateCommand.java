package com.bablsoft.accessflow.core.api;

/**
 * Everything needed to persist one {@code query_estimates} row (issue AF-624). Mirrors
 * {@link PersistAiAnalysisCommand}: the proxy module computes the estimate and hands the values to
 * {@link QueryEstimatePersistenceService}. All value fields are nullable best-effort signals.
 */
public record PersistQueryEstimateCommand(
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
        Integer durationMs) {
}
