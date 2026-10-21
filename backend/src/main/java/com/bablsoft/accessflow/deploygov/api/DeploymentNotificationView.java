package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.util.UUID;

/**
 * Minimal projection the notifications module needs to render a deployment notification (#695).
 * {@code changeTicketRequired}, {@code commitSha}, {@code artifactRef} and {@code runUrl} (#990)
 * feed the change ticket opened for a flagged environment. {@code activeFreezeReason} (#991) is
 * non-null exactly when the environment requires a change ticket and a freeze window is in effect
 * for the target now — the window's reason, or its behavior name when the admin left none.
 */
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
        String aiSummary,
        boolean changeTicketRequired,
        String commitSha,
        String artifactRef,
        String runUrl,
        String activeFreezeReason) {
}
