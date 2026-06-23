package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;

/**
 * One member's per-model contribution to a multi-model analysis (AF-450), surfaced on
 * {@link AiAnalysisResult#modelResults()} so the persistence layer can record cost / latency per
 * model. {@code riskScore}/{@code riskLevel} are {@code null} when the member failed
 * ({@code failed = true}).
 */
public record AiModelResult(
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
