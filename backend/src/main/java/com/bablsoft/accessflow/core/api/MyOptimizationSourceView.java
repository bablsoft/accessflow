package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One source AI analysis feeding the dashboard's optimization-suggestion backlog (AF-498).
 * {@code optimizationsJson} is the raw {@code ai_analyses.optimizations} JSON array (an array of
 * {@code {type,title,rationale,sql}} objects) — the dashboard module parses it and assigns each item
 * a stable {@code {aiAnalysisId}:{index}} id. Carries just enough query/datasource context to render
 * and to deep-link "open in editor".
 */
public record MyOptimizationSourceView(
        UUID aiAnalysisId,
        UUID queryRequestId,
        UUID datasourceId,
        String datasourceName,
        DbType dbType,
        RiskLevel riskLevel,
        String optimizationsJson,
        Instant createdAt) {
}
