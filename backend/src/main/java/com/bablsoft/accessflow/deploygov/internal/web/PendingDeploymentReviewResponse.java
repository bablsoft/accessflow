package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingDeploymentReviewResponse(
        UUID deploymentRequestId, UUID pipelineId, String pipelineName, UUID environmentId,
        String environmentName, UUID submittedByUserId, String version, String commitSha,
        String runUrl, String justification, UUID aiAnalysisId, RiskLevel aiRiskLevel,
        Integer aiRiskScore, String aiSummary, int currentStage, int requiredApprovals,
        Instant scheduledFor, Instant createdAt) {

    static PendingDeploymentReviewResponse from(DeploymentReviewService.PendingDeploymentReview p) {
        return new PendingDeploymentReviewResponse(p.deploymentRequestId(), p.pipelineId(),
                p.pipelineName(), p.environmentId(), p.environmentName(), p.submittedByUserId(),
                p.version(), p.commitSha(), p.runUrl(), p.justification(), p.aiAnalysisId(),
                p.aiRiskLevel(), p.aiRiskScore(), p.aiSummary(), p.currentStage(),
                p.requiredApprovals(), p.scheduledFor(), p.createdAt());
    }

    record Page(List<PendingDeploymentReviewResponse> content, int page, int size,
                long totalElements, int totalPages) {
        static Page from(PageResponse<DeploymentReviewService.PendingDeploymentReview> page) {
            return new Page(
                    page.content().stream().map(PendingDeploymentReviewResponse::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }
}
