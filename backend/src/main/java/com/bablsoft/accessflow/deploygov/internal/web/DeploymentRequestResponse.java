package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DeploymentRequestResponse(
        UUID id,
        UUID pipelineId,
        String pipelineName,
        PipelineProvider provider,
        UUID environmentId,
        String environmentName,
        UUID submittedBy,
        String submittedByEmail,
        String version,
        String commitSha,
        String artifactRef,
        String runUrl,
        String externalRunId,
        Map<String, Object> metadata,
        QueryStatus status,
        SubmissionReason submissionReason,
        String justification,
        UUID aiAnalysisId,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        String aiSummary,
        int requiredApprovals,
        Instant scheduledFor,
        DeploymentOutcome outcome,
        Instant outcomeReportedAt,
        String outcomeDetail,
        Instant createdAt,
        List<DeploymentReviewDecisionResponse> decisions) {

    static DeploymentRequestResponse from(DeploymentRequestView view) {
        return new DeploymentRequestResponse(view.id(), view.pipelineId(), view.pipelineName(),
                view.provider(), view.environmentId(), view.environmentName(), view.submittedBy(),
                view.submittedByEmail(), view.version(), view.commitSha(), view.artifactRef(),
                view.runUrl(), view.externalRunId(), view.metadata(), view.status(),
                view.submissionReason(), view.justification(), view.aiAnalysisId(),
                view.aiRiskLevel(), view.aiRiskScore(), view.aiSummary(), view.requiredApprovals(),
                view.scheduledFor(), view.outcome(), view.outcomeReportedAt(), view.outcomeDetail(),
                view.createdAt(),
                view.decisions().stream().map(DeploymentReviewDecisionResponse::from).toList());
    }
}
