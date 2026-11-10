package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.util.List;

/**
 * An environment's post-deployment verification config on the wire (#1023). Durations bind from
 * ISO-8601 strings. Range checks that need semantics (window bounds, interval vs window, URL
 * scheme) are not made here.
 */
public record DeploymentVerificationConfigRequest(
        @NotNull(message = "{validation.deployment_verification.window.required}")
        Duration window,
        @NotNull(message = "{validation.deployment_verification.sample_interval.required}")
        Duration sampleInterval,
        @NotNull(message = "{validation.deployment_verification.failure_threshold.required}")
        @Min(value = 1, message = "{validation.deployment_verification.failure_threshold.range}")
        @Max(value = 20, message = "{validation.deployment_verification.failure_threshold.range}")
        Integer failureThreshold,
        @NotEmpty(message = "{validation.deployment_verification.checks.required}")
        @Size(max = 10, message = "{validation.deployment_verification.checks.max}")
        List<@NotNull(message = "{validation.deployment_verification.checks.required}")
                @Valid DeploymentVerificationCheckRequest> checks) {

    DeploymentVerificationConfig toConfig() {
        return new DeploymentVerificationConfig(window, sampleInterval, failureThreshold,
                checks.stream().map(DeploymentVerificationCheckRequest::toCheck).toList());
    }

    static DeploymentVerificationConfig toConfigOrNull(DeploymentVerificationConfigRequest request) {
        return request == null ? null : request.toConfig();
    }
}
