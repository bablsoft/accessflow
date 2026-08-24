package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.SubmitDeploymentRequestCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Trigger body. {@code environment} is the environment name within the pipeline; {@code metadata}
 * is the free-form release context (changelog, commit list, diff summary) the AI analyzes.
 */
public record SubmitDeploymentRequestRequest(
        @NotNull(message = "{validation.deployment_request.pipeline.required}")
        UUID pipelineId,

        @NotBlank(message = "{validation.deployment_request.environment.required}")
        @Size(max = 255, message = "{validation.deployment_request.environment.size}")
        String environment,

        @NotBlank(message = "{validation.deployment_request.version.required}")
        @Size(max = 255, message = "{validation.deployment_request.version.size}")
        String version,

        @Size(max = 64, message = "{validation.deployment_request.commit_sha.size}")
        String commitSha,

        String artifactRef,
        String runUrl,
        String externalRunId,
        Map<String, Object> metadata,
        String justification,
        Instant scheduledFor) {

    SubmitDeploymentRequestCommand toCommand(UUID organizationId, UUID submitterUserId, boolean admin,
                                             String submittedIp) {
        return new SubmitDeploymentRequestCommand(pipelineId, environment, organizationId,
                submitterUserId, admin, version, commitSha, artifactRef, runUrl, externalRunId,
                metadata, justification, scheduledFor, null, submittedIp);
    }
}
