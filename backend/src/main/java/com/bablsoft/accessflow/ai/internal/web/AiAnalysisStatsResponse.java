package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.core.api.AiAnalysisIssueCategoryView;
import com.bablsoft.accessflow.core.api.AiAnalysisRiskScoreBucketView;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsRaw;
import com.bablsoft.accessflow.core.api.AiAnalysisSubmitterView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Jackson globally converts camelCase → snake_case (application.yml: spring.jackson.property-naming-strategy=SNAKE_CASE).
record AiAnalysisStatsResponse(
        List<RiskScorePointResponse> riskScoreOverTime,
        List<IssueCategoryResponse> topIssueCategories,
        List<TopSubmitterResponse> topSubmitters
) {

    static AiAnalysisStatsResponse from(AiAnalysisStatsRaw raw) {
        return new AiAnalysisStatsResponse(
                raw.riskScoreOverTime().stream().map(RiskScorePointResponse::from).toList(),
                raw.topIssueCategories().stream().map(IssueCategoryResponse::from).toList(),
                raw.topSubmitters().stream().map(TopSubmitterResponse::from).toList());
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
}
