package com.bablsoft.accessflow.apigov.internal.web;

import jakarta.validation.constraints.Min;

/** {@code refreshIntervalMinutes} {@code null} disables the scheduled refresh. */
public record UpdateApiSchemaRefreshPolicyRequest(
        @Min(value = 1, message = "{validation.api_schema.refresh_interval.min}")
        Integer refreshIntervalMinutes) {
}
