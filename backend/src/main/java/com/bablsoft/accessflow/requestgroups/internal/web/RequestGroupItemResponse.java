package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.UUID;

record RequestGroupItemResponse(
        UUID id,
        int sequenceOrder,
        RequestGroupTargetKind targetKind,
        UUID datasourceId,
        String datasourceName,
        String sqlText,
        QueryType queryType,
        boolean transactional,
        UUID apiConnectorId,
        String apiConnectorName,
        String operationId,
        String verb,
        String requestPath,
        UUID aiAnalysisId,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        AiAnalysisDetail aiAnalysis,
        RequestGroupItemStatus status,
        Integer responseStatusCode,
        Long rowsAffected,
        String errorMessage,
        Integer durationMs,
        Instant executedAt) {

    static RequestGroupItemResponse from(RequestGroupItemView v) {
        return new RequestGroupItemResponse(v.id(), v.sequenceOrder(), v.targetKind(), v.datasourceId(),
                v.datasourceName(), v.sqlText(), v.queryType(), v.transactional(), v.apiConnectorId(),
                v.apiConnectorName(), v.operationId(), v.verb(), v.requestPath(), v.aiAnalysisId(),
                v.aiRiskLevel(), v.aiRiskScore(), AiAnalysisDetail.from(v.aiAnalysis()), v.status(),
                v.responseStatusCode(), v.rowsAffected(), v.errorMessage(), v.durationMs(),
                v.executedAt());
    }

    /** Full embedded member analysis — same shape as the query-detail {@code aiAnalysis} (AF-531). */
    record AiAnalysisDetail(
            UUID id,
            RiskLevel riskLevel,
            int riskScore,
            String summary,
            @JsonRawValue String issues,
            @JsonRawValue String optimizations,
            boolean missingIndexesDetected,
            Long affectsRowEstimate,
            AiProviderType aiProvider,
            String aiModel,
            int promptTokens,
            int completionTokens,
            boolean failed,
            String errorMessage) {

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
                    src.optimizationsJson() != null ? src.optimizationsJson() : "[]",
                    src.missingIndexesDetected(),
                    src.affectsRowEstimate(),
                    src.aiProvider(),
                    src.aiModel(),
                    src.promptTokens(),
                    src.completionTokens(),
                    src.failed(),
                    src.errorMessage());
        }
    }
}
