package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/** A rollback follow-up review record (#693) — see {@link DeploymentRollbackReviewService}. */
public record DeploymentRollbackReviewView(
        UUID id,
        UUID deploymentRequestId,
        UUID organizationId,
        UUID pipelineId,
        UUID environmentId,
        UUID submittedBy,
        String outcomeDetail,
        DeploymentRollbackReviewStatus status,
        UUID reviewedBy,
        String reviewComment,
        Instant reviewedAt,
        Instant createdAt) {
}
