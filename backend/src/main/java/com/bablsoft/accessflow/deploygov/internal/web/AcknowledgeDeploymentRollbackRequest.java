package com.bablsoft.accessflow.deploygov.internal.web;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /deployment-rollback-reviews/{id}/acknowledge} (#693). */
public record AcknowledgeDeploymentRollbackRequest(
        @Size(max = 2000, message = "{validation.deployment_rollback_review.comment.size}")
        String comment) {
}
