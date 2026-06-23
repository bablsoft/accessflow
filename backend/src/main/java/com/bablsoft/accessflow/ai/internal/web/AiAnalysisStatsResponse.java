package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.core.api.AiAnalysisIssueCategoryView;
import com.bablsoft.accessflow.core.api.AiAnalysisModelStatView;
import com.bablsoft.accessflow.core.api.AiAnalysisRiskScoreBucketView;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsRaw;
import com.bablsoft.accessflow.core.api.AiAnalysisSubmitterView;
import com.bablsoft.accessflow.core.api.AiProviderType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Jackson globally converts camelCase → snake_case (application.yml: spring.jackson.property-naming-strategy=SNAKE_CASE).
record AiAnalysisStatsResponse(
        List<RiskScorePointResponse> riskScoreOverTime,
        List<IssueCategoryResponse> topIssueCategories,
        List<TopSubmitterResponse> topSubmitters,
        List<PerModelStatResponse> perModelStats
) {

    static AiAnalysisStatsResponse from(AiAnalysisStatsRaw raw) {
        return new AiAnalysisStatsResponse(
                raw.riskScoreOverTime().stream().map(RiskScorePointResponse::from).toList(),
                raw.topIssueCategories().stream().map(IssueCategoryResponse::from).toList(),
                raw.topSubmitters().stream().map(TopSubmitterResponse::from).toList(),
                raw.perModelStats().stream().map(PerModelStatResponse::from).toList());
    }

    record RiskScorePointResponse(
            LocalDate date,
            BigDecimal successAvgRiskScore,
            long totalCount,
            long successCount) {

        static RiskScorePointResponse from(AiAnalysisRiskScoreBucketView v) {
            return new RiskScorePointResponse(v.date(), v.successAvgRiskScore(),
                    v.totalCount(), v.successCount());
        }
    }

    record IssueCategoryResponse(String category, long count) {

        static IssueCategoryResponse from(AiAnalysisIssueCategoryView v) {
            return new IssueCategoryResponse(v.category(), v.count());
        }
    }

    record TopSubmitterResponse(
            UUID userId,
            String email,
            String displayName,
            long count) {

        static TopSubmitterResponse from(AiAnalysisSubmitterView v) {
            return new TopSubmitterResponse(v.userId(), v.email(), v.displayName(), v.count());
        }
    }

    record PerModelStatResponse(
            AiProviderType provider,
            String model,
            long analysisCount,
            long totalPromptTokens,
            long totalCompletionTokens,
            BigDecimal avgLatencyMs,
            BigDecimal avgRiskScore) {

        static PerModelStatResponse from(AiAnalysisModelStatView v) {
            return new PerModelStatResponse(v.provider(), v.model(), v.analysisCount(),
                    v.totalPromptTokens(), v.totalCompletionTokens(), v.avgLatencyMs(),
                    v.avgRiskScore());
        }
    }
}
