package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /deployment-requests/{id}/outcome} (#693). */
public record ReportDeploymentOutcomeRequest(
        @NotNull(message = "{validation.deployment_outcome.outcome.required}")
        DeploymentOutcome outcome,

        @Size(max = 4000, message = "{validation.deployment_outcome.detail.size}")
        String detail) {
}
