package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthCheckView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthStatus;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthView;

import java.time.Instant;
import java.util.List;

public record ApiConnectorHealthResponse(
        ApiConnectorHealthStatus status,
        Instant lastProbeAt,
        int consecutiveFailures,
        Integer healthCheckIntervalMinutes,
        List<Check> recentChecks) {

    public record Check(Instant checkedAt, boolean success, String message, int durationMs) {

        static Check from(ApiConnectorHealthCheckView v) {
            return new Check(v.checkedAt(), v.success(), v.message(), v.durationMs());
        }
    }

    static ApiConnectorHealthResponse from(ApiConnectorHealthView v) {
        return new ApiConnectorHealthResponse(v.status(), v.lastProbeAt(), v.consecutiveFailures(),
                v.healthCheckIntervalMinutes(), v.recentChecks().stream().map(Check::from).toList());
    }
}
