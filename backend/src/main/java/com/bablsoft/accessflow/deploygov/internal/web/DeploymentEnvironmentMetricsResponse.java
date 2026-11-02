package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentMetricsView;
import com.bablsoft.accessflow.deploygov.internal.web.DeploymentMetricsResponse.ApprovalLatencyResponse;
import com.bablsoft.accessflow.deploygov.internal.web.DeploymentMetricsResponse.ChangeFailureRateResponse;
import com.bablsoft.accessflow.deploygov.internal.web.DeploymentMetricsResponse.FrequencyResponse;
import com.bablsoft.accessflow.deploygov.internal.web.DeploymentMetricsResponse.LeadTimeResponse;
import com.bablsoft.accessflow.deploygov.internal.web.DeploymentMetricsResponse.PeriodResponse;
import com.bablsoft.accessflow.deploygov.internal.web.DeploymentMetricsResponse.TimeToRestoreResponse;
import com.bablsoft.accessflow.deploygov.internal.web.DeploymentMetricsResponse.TimeoutRateResponse;

import java.util.List;
import java.util.UUID;

/** Per-(pipeline, environment) metrics (#987): the #984 blocks without {@code daily}. */
public record DeploymentEnvironmentMetricsResponse(
        PeriodResponse period,
        List<EnvironmentResponse> environments) {

    record EnvironmentResponse(
            UUID pipelineId,
            String pipelineName,
            UUID environmentId,
            String environmentName,
            FrequencyResponse deploymentFrequency,
            LeadTimeResponse leadTime,
            ChangeFailureRateResponse changeFailureRate,
            TimeToRestoreResponse timeToRestore,
            ApprovalLatencyResponse approvalLatency,
            TimeoutRateResponse timeoutRate,
            long breakGlassCount) {
    }

    static DeploymentEnvironmentMetricsResponse from(DeploymentEnvironmentMetricsView view) {
        return new DeploymentEnvironmentMetricsResponse(
                new PeriodResponse(view.period().from(), view.period().to()),
                view.environments().stream().map(environment -> {
                    var metrics = DeploymentMetricsResponse.from(environment.metrics());
                    return new EnvironmentResponse(environment.pipelineId(),
                            environment.pipelineName(), environment.environmentId(),
                            environment.environmentName(), metrics.deploymentFrequency(),
                            metrics.leadTime(), metrics.changeFailureRate(),
                            metrics.timeToRestore(), metrics.approvalLatency(),
                            metrics.timeoutRate(), metrics.breakGlassCount());
                }).toList());
    }
}
