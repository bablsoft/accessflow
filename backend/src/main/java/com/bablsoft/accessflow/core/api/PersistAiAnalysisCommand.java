package com.bablsoft.accessflow.core.api;

import java.util.List;

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
        String errorMessage,
        List<PersistAiModelResultCommand> modelResults) {

    public PersistAiAnalysisCommand {
        modelResults = modelResults == null ? List.of() : List.copyOf(modelResults);
    }

    /** Convenience constructor for callers that record no per-model breakdown (sentinel rows, tests). */
    public PersistAiAnalysisCommand(AiProviderType aiProvider, String aiModel, int riskScore,
            RiskLevel riskLevel, String summary, String issuesJson, String optimizationsJson,
            boolean missingIndexesDetected, Long affectsRowEstimate, int promptTokens,
            int completionTokens, boolean failed, String errorMessage) {
        this(aiProvider, aiModel, riskScore, riskLevel, summary, issuesJson, optimizationsJson,
                missingIndexesDetected, affectsRowEstimate, promptTokens, completionTokens, failed,
                errorMessage, List.of());
    }
}
