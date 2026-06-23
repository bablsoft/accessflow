package com.bablsoft.accessflow.core.api;

import java.util.List;

public record AiAnalysisStatsRaw(
        List<AiAnalysisRiskScoreBucketView> riskScoreOverTime,
        List<AiAnalysisIssueCategoryView> topIssueCategories,
        List<AiAnalysisSubmitterView> topSubmitters,
        List<AiAnalysisModelStatView> perModelStats
) {

    public AiAnalysisStatsRaw {
        riskScoreOverTime = riskScoreOverTime == null ? List.of() : List.copyOf(riskScoreOverTime);
        topIssueCategories = topIssueCategories == null ? List.of() : List.copyOf(topIssueCategories);
        topSubmitters = topSubmitters == null ? List.of() : List.copyOf(topSubmitters);
        perModelStats = perModelStats == null ? List.of() : List.copyOf(perModelStats);
    }

    /** Convenience constructor for callers that do not report per-model stats (tests). */
    public AiAnalysisStatsRaw(List<AiAnalysisRiskScoreBucketView> riskScoreOverTime,
            List<AiAnalysisIssueCategoryView> topIssueCategories,
            List<AiAnalysisSubmitterView> topSubmitters) {
        this(riskScoreOverTime, topIssueCategories, topSubmitters, List.of());
    }
}
