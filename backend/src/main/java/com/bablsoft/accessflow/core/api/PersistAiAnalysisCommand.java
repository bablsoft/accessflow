package com.bablsoft.accessflow.core.api;

public record PersistAiAnalysisCommand(
        AiProviderType aiProvider,
        String aiModel,
        int riskScore,
        RiskLevel riskLevel,
        String summary,
        String issuesJson,
        String optimizationsJson,
        boolean missingIndexesDetected,
        Long affectsRowEstimate,
        int promptTokens,
        int completionTokens,
        boolean failed,
        String errorMessage) {
}
