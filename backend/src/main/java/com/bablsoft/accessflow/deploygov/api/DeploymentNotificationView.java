package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.util.UUID;

/** Minimal projection the notifications module needs to render a deployment notification (#695). */
public record DeploymentNotificationView(
        UUID id,
        UUID organizationId,
        UUID pipelineId,
        String pipelineName,
        String environmentName,
        String version,
        UUID submittedByUserId,
        QueryStatus status,
        SubmissionReason submissionReason,
        String justification,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        String aiSummary) {
}
