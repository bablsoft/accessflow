package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One verification check on the wire (#1023) — a flat shape keyed by {@code kind}; only the fields
 * of that kind are read. {@code expectedStatus} defaults to 200 and {@code noDataIsFailure} to
 * {@code true} (an empty PromQL result fails closed).
 */
public record DeploymentVerificationCheckRequest(
        @NotNull(message = "{validation.deployment_verification.check.kind.required}")
        VerificationCheckKind kind,
        @NotBlank(message = "{validation.deployment_verification.check.name.required}")
        @Size(max = 64, message = "{validation.deployment_verification.check.name.size}")
        String name,
        @Size(max = 2048, message = "{validation.deployment_verification.check.url.size}")
        String url,
        @Min(value = 100, message = "{validation.deployment_verification.check.expected_status.range}")
        @Max(value = 599, message = "{validation.deployment_verification.check.expected_status.range}")
        Integer expectedStatus,
        @Size(max = 1024, message = "{validation.deployment_verification.check.body_contains.size}")
        String bodyContains,
        @Size(max = 2048, message = "{validation.deployment_verification.check.url.size}")
        String prometheusUrl,
        @Size(max = 4096, message = "{validation.deployment_verification.check.query.size}")
        String query,
        VerificationComparator comparator,
        Double threshold,
        Boolean noDataIsFailure) {

    static final int DEFAULT_EXPECTED_STATUS = 200;

    /** The fields the check's kind needs are present; an unset kind is reported by its own rule. */
    @AssertTrue(message = "{validation.deployment_verification.check.kind_fields.required}")
    public boolean isKindFieldsPresent() {
        if (kind == null) {
            return true;
        }
        return switch (kind) {
            case HTTP_HEALTH -> hasText(url);
            case PROMQL_THRESHOLD -> hasText(prometheusUrl) && hasText(query)
                    && comparator != null && threshold != null;
        };
    }

    VerificationCheck toCheck() {
        return switch (kind) {
            case HTTP_HEALTH -> new HttpHealthCheck(name, url,
                    expectedStatus == null ? DEFAULT_EXPECTED_STATUS : expectedStatus,
                    hasText(bodyContains) ? bodyContains : null);
            case PROMQL_THRESHOLD -> new PromqlThresholdCheck(name, prometheusUrl, query, comparator,
                    threshold, noDataIsFailure == null || noDataIsFailure);
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
