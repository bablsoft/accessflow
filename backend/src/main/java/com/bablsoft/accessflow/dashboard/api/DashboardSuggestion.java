package com.bablsoft.accessflow.dashboard.api;

import com.bablsoft.accessflow.ai.api.OptimizationType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.UUID;

/**
 * One actionable AI optimization suggestion in a user's dashboard backlog (AF-498), derived from a
 * single item of an {@code ai_analyses.optimizations[]} array. {@code id} is the stable
 * {@code {aiAnalysisId}:{index}} handle used by the dismiss endpoint and the frontend. {@code sql}
 * is the ready-to-run rewrite / index statement loaded into the editor on "open in editor" — never
 * executed here.
 */
public record DashboardSuggestion(
        String id,
        UUID aiAnalysisId,
        int index,
        UUID queryRequestId,
        UUID datasourceId,
        String datasourceName,
        DbType dbType,
        RiskLevel riskLevel,
        OptimizationType type,
        String title,
        String rationale,
        String sql,
        DashboardSuggestionStatus status,
        Instant analyzedAt) {
}
