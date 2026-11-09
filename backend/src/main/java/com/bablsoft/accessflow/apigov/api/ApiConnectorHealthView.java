package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;

/** Scheduled-health read model of a connector (#894); {@code recentChecks} is newest first. */
public record ApiConnectorHealthView(
        ApiConnectorHealthStatus status,
        Instant lastProbeAt,
        int consecutiveFailures,
        Integer healthCheckIntervalMinutes,
        List<ApiConnectorHealthCheckView> recentChecks) {

    public ApiConnectorHealthView {
        recentChecks = recentChecks == null ? List.of() : List.copyOf(recentChecks);
    }
}
