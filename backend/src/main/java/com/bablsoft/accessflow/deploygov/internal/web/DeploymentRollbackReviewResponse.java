package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewView;

import java.time.Instant;
import java.util.UUID;

public record DeploymentRollbackReviewResponse(
        UUID id,
        UUID deploymentRequestId,
        UUID pipelineId,
        UUID environmentId,
        UUID submittedBy,
        String outcomeDetail,
        DeploymentRollbackReviewStatus status,
        UUID reviewedBy,
        String reviewComment,
        Instant reviewedAt,
        Instant createdAt) {

    static DeploymentRollbackReviewResponse from(DeploymentRollbackReviewView view) {
        return new DeploymentRollbackReviewResponse(view.id(), view.deploymentRequestId(),
                view.pipelineId(), view.environmentId(), view.submittedBy(), view.outcomeDetail(),
                view.status(), view.reviewedBy(), view.reviewComment(), view.reviewedAt(),
                view.createdAt());
    }
}
