package com.bablsoft.accessflow.compliance.api;

import java.util.UUID;

/**
 * One (pipeline, environment) pair for the {@link ComplianceReportType#DEPLOYMENT_METRICS} report
 * (#986), copied from {@code deploygov.api.DeploymentMetricsView} — never re-derived. Percentiles
 * are whole seconds and {@code null} on an empty sample; rates are {@code null} when their
 * denominator is {@code 0}; {@code leadTimeSource} is the enum name, {@code null} with no
 * deployment.
 */
public record DeploymentMetricsReportRow(
        UUID pipelineId,
        String pipelineName,
        UUID environmentId,
        String environmentName,
        long deployments,
        Long leadTimeP50Seconds,
        Long leadTimeP95Seconds,
        String leadTimeSource,
        Double changeFailureRate,
        long failed,
        long rolledBack,
        long unreported,
        Long timeToRestoreP50Seconds,
        Long timeToRestoreP95Seconds,
        long openIncidents,
        Long approvalLatencyP50Seconds,
        Long approvalLatencyP95Seconds,
        Double timeoutRate,
        long timedOut,
        long breakGlassCount) {
}
