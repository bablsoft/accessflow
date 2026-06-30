package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;

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
                v.aiRiskLevel(), v.aiRiskScore(), v.status(), v.responseStatusCode(), v.rowsAffected(),
                v.errorMessage(), v.durationMs(), v.executedAt());
    }
}
