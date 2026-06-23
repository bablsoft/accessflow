package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.List;

public record AiAnalysisResult(
        int riskScore,
        RiskLevel riskLevel,
        String summary,
        List<AiIssue> issues,
        boolean missingIndexesDetected,
        Long affectsRowEstimate,
        AiProviderType aiProvider,
        String aiModel,
        int promptTokens,
        int completionTokens,
        List<OptimizationSuggestion> optimizations,
        List<AiModelResult> modelResults) {

    public AiAnalysisResult {
        modelResults = modelResults == null ? List.of() : List.copyOf(modelResults);
    }

    /**
     * Convenience constructor for the single-model path (parsers, provider strategies, tests). The
     * orchestrator is the only producer that populates {@link #modelResults()}; everyone else leaves
     * it empty.
     */
    public AiAnalysisResult(int riskScore, RiskLevel riskLevel, String summary, List<AiIssue> issues,
            boolean missingIndexesDetected, Long affectsRowEstimate, AiProviderType aiProvider,
            String aiModel, int promptTokens, int completionTokens,
            List<OptimizationSuggestion> optimizations) {
        this(riskScore, riskLevel, summary, issues, missingIndexesDetected, affectsRowEstimate,
                aiProvider, aiModel, promptTokens, completionTokens, optimizations, List.of());
    }
}
