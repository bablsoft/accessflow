package com.partqam.accessflow.workflow.internal.web;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.workflow.api.ReviewService.PendingReview;

import java.time.Instant;
import java.util.UUID;

public record PendingReviewItem(
        UUID id,
        DatasourceSummary datasource,
        SubmitterSummary submittedBy,
        String sqlText,
        QueryType queryType,
        String justification,
        AiAnalysisSummary aiAnalysis,
        int currentStage,
        Instant createdAt) {

    public static PendingReviewItem from(PendingReview pending) {
        return new PendingReviewItem(
                pending.queryRequestId(),
                new DatasourceSummary(pending.datasourceId(), pending.datasourceName()),
                new SubmitterSummary(pending.submittedByUserId(), pending.submittedByEmail()),
                pending.sqlText(),
                pending.queryType(),
                pending.justification(),
                pending.aiAnalysisId() == null ? null : new AiAnalysisSummary(
                        pending.aiAnalysisId(),
                        pending.aiRiskLevel(),
                        pending.aiRiskScore(),
                        pending.aiSummary()),
                pending.currentStage(),
                pending.createdAt());
    }

    public record DatasourceSummary(UUID id, String name) {
    }

    public record SubmitterSummary(UUID id, String email) {
    }

    public record AiAnalysisSummary(UUID id, RiskLevel riskLevel, Integer riskScore,
                                    String summary) {
    }
}
