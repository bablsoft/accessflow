package com.bablsoft.accessflow.core.api;

import java.util.List;

public record AiAnalysisStatsRaw(
        List<AiAnalysisRiskScoreBucketView> riskScoreOverTime,
        List<AiAnalysisIssueCategoryView> topIssueCategories,
        List<AiAnalysisSubmitterView> topSubmitters
) {

    public AiAnalysisStatsRaw {
        riskScoreOverTime = riskScoreOverTime == null ? List.of() : List.copyOf(riskScoreOverTime);
        topIssueCategories = topIssueCategories == null ? List.of() : List.copyOf(topIssueCategories);
        topSubmitters = topSubmitters == null ? List.of() : List.copyOf(topSubmitters);
    }
}
