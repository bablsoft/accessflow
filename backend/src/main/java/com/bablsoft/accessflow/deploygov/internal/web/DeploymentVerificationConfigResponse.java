package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;

import java.time.Duration;
import java.util.List;

/**
 * An environment's post-deployment verification config in a response (#1023). Durations are
 * ISO-8601 strings, the same form the request takes.
 */
public record DeploymentVerificationConfigResponse(
        String window,
        String sampleInterval,
        int failureThreshold,
        List<Check> checks) {

    /** One check, flat like the request; fields of the other kind are null. */
    public record Check(
            VerificationCheckKind kind,
            String name,
            String url,
            Integer expectedStatus,
            String bodyContains,
            String prometheusUrl,
            String query,
            VerificationComparator comparator,
            Double threshold,
            Boolean noDataIsFailure) {

        static Check from(VerificationCheck check) {
            return switch (check) {
                case HttpHealthCheck http -> new Check(http.kind(), http.name(), http.url(),
                        http.expectedStatus(), http.bodyContains(), null, null, null, null, null);
                case PromqlThresholdCheck promql -> new Check(promql.kind(), promql.name(), null,
                        null, null, promql.prometheusUrl(), promql.query(), promql.comparator(),
                        promql.threshold(), promql.noDataIsFailure());
            };
        }
    }

    static DeploymentVerificationConfigResponse from(DeploymentVerificationConfig config) {
        if (config == null) {
            return null;
        }
        return new DeploymentVerificationConfigResponse(isoOrNull(config.window()),
                isoOrNull(config.sampleInterval()),
                config.failureThreshold(), config.checks().stream().map(Check::from).toList());
    }

    private static String isoOrNull(Duration duration) {
        return duration == null ? null : duration.toString();
    }
}
