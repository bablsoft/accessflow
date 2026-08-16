package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.workflow.api.ReviewService.PendingReview;

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
        Double approvalProbability,
        int currentStage,
        Instant createdAt,
        /**
         * The delegator whose out-of-office delegation made this row visible (#622), or null when
         * the reviewer is eligible in their own right — even if a delegation would also cover it.
         */
        DelegatorSummary delegatedFor) {

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
                pending.approvalProbability(),
                pending.currentStage(),
                pending.createdAt(),
                pending.delegatedForUserId() == null ? null : new DelegatorSummary(
                        pending.delegatedForUserId(),
                        pending.delegatedForEmail(),
                        pending.delegatedForDisplayName()));
    }

    public record DatasourceSummary(UUID id, String name) {
    }

    public record SubmitterSummary(UUID id, String email) {
    }

    public record DelegatorSummary(UUID id, String email, String displayName) {
    }

    public record AiAnalysisSummary(UUID id, RiskLevel riskLevel, Integer riskScore,
                                    String summary) {
    }
}
