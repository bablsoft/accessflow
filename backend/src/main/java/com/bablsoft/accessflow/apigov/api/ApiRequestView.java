package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view of a governed API request. {@code responseSnapshot} (the size-capped, field-masked
 * response body) and {@code decisions} are populated on the detail view only. {@code submittedByEmail}
 * is resolved for the list/detail; {@code traceId}/{@code spanId} are the W3C trace-context ids.
 */
public record ApiRequestView(
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
        Instant scheduledFor,
        String traceId,
        String spanId,
        Integer responseStatusCode,
        Integer responseDurationMs,
        Long responseBytes,
        boolean responseTruncated,
        String responseSnapshot,
        String responseContentType,
        String errorMessage,
        Instant createdAt,
        List<ApiReviewDecisionView> decisions) {
}
