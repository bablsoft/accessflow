package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Window and scope of a deployment-metrics read (#984). Every field is optional: with neither
 * bound the window is the trailing 30 days, a lone bound spans 30 days from it ({@code [from, to)}
 * either way), {@code environment} is a
 * case-insensitive environment name, and {@code pipelineId} narrows the org-wide read only (the
 * per-pipeline read ignores it).
 */
public record DeploymentMetricsFilter(
        Instant from,
        Instant to,
        String environment,
        UUID pipelineId) {
}
