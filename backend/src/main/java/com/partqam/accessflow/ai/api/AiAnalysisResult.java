package com.partqam.accessflow.ai.api;

import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.RiskLevel;

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
        int completionTokens) {
}
