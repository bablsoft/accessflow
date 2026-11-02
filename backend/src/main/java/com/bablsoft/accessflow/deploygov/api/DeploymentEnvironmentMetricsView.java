package com.bablsoft.accessflow.deploygov.api;

import java.util.List;
import java.util.UUID;

/**
 * Deployment metrics broken down per (pipeline, environment) over one window (#987). Each entry's
 * {@code metrics} is exactly what {@link DeploymentMetricsService#organizationMetrics} returns
 * when narrowed to that pipeline and environment.
 */
public record DeploymentEnvironmentMetricsView(
        DeploymentMetricsView.Period period,
        List<EnvironmentMetrics> environments) {

    public record EnvironmentMetrics(
            UUID pipelineId,
            String pipelineName,
            UUID environmentId,
            String environmentName,
            DeploymentMetricsView metrics) {
    }
}
