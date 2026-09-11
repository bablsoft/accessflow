package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * One hypothetical deployment to trace (issue AF-967).
 *
 * <p>{@code at} is what makes this endpoint answer the question no screen does — "would a release
 * this Friday evening be frozen?" — so it is an ordinary optional input rather than a debug knob. It
 * defaults to now.
 */
record SimulateDeploymentRequest(
        @NotNull(message = "{validation.deployment_simulation.user_id_required}") UUID userId,
        @NotNull(message = "{validation.deployment_simulation.pipeline_id_required}") UUID pipelineId,
        @NotNull(message = "{validation.deployment_simulation.environment_id_required}")
        UUID environmentId,
        @NotBlank(message = "{validation.deployment_simulation.version_required}")
        @Size(max = 255, message = "{validation.deployment_simulation.version_too_long}")
        String version,
        AiOutcome aiOutcome,
        RiskLevel riskLevel,
        Instant scheduledFor,
        Instant at) {

    DeploymentSimulationInput toInput() {
        return new DeploymentSimulationInput(userId, pipelineId, environmentId, version, aiOutcome,
                riskLevel, scheduledFor, at);
    }
}
