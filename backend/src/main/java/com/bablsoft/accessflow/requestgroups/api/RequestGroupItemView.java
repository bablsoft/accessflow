package com.bablsoft.accessflow.requestgroups.api;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read model for a single group member (query or API call) plus its AI risk and run outcome. */
public record RequestGroupItemView(
        UUID id,
        int sequenceOrder,
        RequestGroupTargetKind targetKind,
        // QUERY
        UUID datasourceId,
        String datasourceName,
        String sqlText,
        QueryType queryType,
        boolean transactional,
        // API_CALL
        UUID apiConnectorId,
        String apiConnectorName,
        String operationId,
        String verb,
        String requestPath,
        // API_CALL composition — populated on the group detail view only so a DRAFT can be
        // re-opened for editing (issue #559); null/empty on list views
        Map<String, String> requestHeaders,
        Map<String, String> queryParams,
        ApiBodyType bodyType,
        String requestContentType,
        String requestBody,
        List<ApiFormField> formFields,
        String binaryFilename,
        // AI
        UUID aiAnalysisId,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        /** Full embedded analysis — populated on the group detail view only (AF-531). */
        QueryDetailView.AiAnalysisDetail aiAnalysis,
        // Outcome
        RequestGroupItemStatus status,
        Integer responseStatusCode,
        Long rowsAffected,
        String errorMessage,
        Integer durationMs,
        Instant executedAt) {
}
