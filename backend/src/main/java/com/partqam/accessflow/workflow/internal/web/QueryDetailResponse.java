package com.partqam.accessflow.workflow.internal.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.QueryDetailView;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.UUID;

/** Response body for {@code GET /queries/{id}}. */
public record QueryDetailResponse(
        UUID id,
        QueryListItem.DatasourceRef datasource,
        QueryListItem.SubmitterRef submittedBy,
        String sqlText,
        QueryType queryType,
        QueryStatus status,
        String justification,
        AiAnalysisDetail aiAnalysis,
        Long rowsAffected,
        Integer durationMs,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static QueryDetailResponse from(QueryDetailView view) {
        return new QueryDetailResponse(
                view.id(),
                new QueryListItem.DatasourceRef(view.datasourceId(), view.datasourceName()),
                new QueryListItem.SubmitterRef(view.submittedByUserId(),
                        view.submittedByEmail(), view.submittedByDisplayName()),
                view.sqlText(),
                view.queryType(),
                view.status(),
                view.justification(),
                AiAnalysisDetail.from(view.aiAnalysis()),
                view.rowsAffected(),
                view.durationMs(),
                view.errorMessage(),
                view.createdAt(),
                view.updatedAt());
    }

    public record AiAnalysisDetail(
            UUID id,
            RiskLevel riskLevel,
            int riskScore,
            String summary,
            @JsonRawValue String issues,
            boolean missingIndexesDetected,
            Long affectsRowEstimate,
            AiProviderType aiProvider,
            String aiModel,
            int promptTokens,
            int completionTokens) {

        static AiAnalysisDetail from(QueryDetailView.AiAnalysisDetail src) {
            if (src == null) {
                return null;
            }
            return new AiAnalysisDetail(
                    src.id(),
                    src.riskLevel(),
                    src.riskScore(),
                    src.summary(),
                    src.issuesJson() != null ? src.issuesJson() : "[]",
                    src.missingIndexesDetected(),
                    src.affectsRowEstimate(),
                    src.aiProvider(),
                    src.aiModel(),
                    src.promptTokens(),
                    src.completionTokens());
        }
    }
}
