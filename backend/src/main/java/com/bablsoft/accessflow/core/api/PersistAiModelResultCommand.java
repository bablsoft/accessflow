package com.bablsoft.accessflow.core.api;

/**
 * One member's contribution to a persisted AI analysis (AF-450). Written to
 * {@code ai_analysis_model_result} alongside the aggregate {@code ai_analyses} row — one per
 * participating model, including failed members ({@code failed=true}, {@code riskScore}/{@code
 * riskLevel} null).
 */
public record PersistAiModelResultCommand(
        AiProviderType provider,
        String model,
        Integer riskScore,
        RiskLevel riskLevel,
        double weight,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        boolean failed,
        String errorMessage) {
}
