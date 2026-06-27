package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.util.UUID;

/** Minimal projection the notifications module needs to render an API-request notification. */
public record ApiRequestNotificationView(
        UUID id,
        UUID organizationId,
        UUID connectorId,
        String connectorName,
        UUID submittedByUserId,
        String verb,
        String requestPath,
        SubmissionReason submissionReason,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        String aiSummary) {
}
