package com.bablsoft.accessflow.core.api;

import java.math.BigDecimal;

/**
 * Per-model aggregate over {@code ai_analysis_model_result} for the admin dashboard (AF-450) —
 * one row per distinct {@code (provider, model)} within the requested window/datasource. Surfaces
 * the model's token cost and average latency so multi-model orchestration can be compared.
 */
public record AiAnalysisModelStatView(
        AiProviderType provider,
        String model,
        long analysisCount,
        long totalPromptTokens,
        long totalCompletionTokens,
        BigDecimal avgLatencyMs,
        BigDecimal avgRiskScore
) {
}
