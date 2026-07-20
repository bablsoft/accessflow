package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiRequestView;
import com.bablsoft.accessflow.apigov.api.ApiReviewDecisionView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ApiRequestResponse(
        UUID id,
        UUID connectorId,
        String connectorName,
        UUID submittedBy,
        String submittedByEmail,
        String operationId,
        String verb,
        String requestPath,
        boolean write,
        QueryStatus status,
        SubmissionReason submissionReason,
        String justification,
        UUID aiAnalysisId,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        String aiSummary,
        ApiBodyType bodyType,
        Map<String, String> variableOverrides,
        Instant scheduledFor,
        String traceId,
        String spanId,
        Integer responseStatusCode,
        Integer responseDurationMs,
        Long responseBytes,
        boolean responseTruncated,
        String responseSnapshot,
        boolean responseSnapshotPreviewTruncated,
        String responseContentType,
        String errorMessage,
        Instant createdAt,
        List<ApiReviewDecisionView> decisions) {

    static ApiRequestResponse from(ApiRequestView v) {
        return new ApiRequestResponse(v.id(), v.connectorId(), v.connectorName(), v.submittedBy(),
                v.submittedByEmail(), v.operationId(), v.verb(), v.requestPath(), v.write(), v.status(),
                v.submissionReason(), v.justification(), v.aiAnalysisId(), v.aiRiskLevel(), v.aiRiskScore(),
                v.aiSummary(), v.bodyType(), v.variableOverrides(), v.scheduledFor(), v.traceId(),
                v.spanId(),
                v.responseStatusCode(), v.responseDurationMs(), v.responseBytes(), v.responseTruncated(),
                v.responseSnapshot(), v.responseSnapshotPreviewTruncated(), v.responseContentType(),
                v.errorMessage(), v.createdAt(), v.decisions());
    }
}
