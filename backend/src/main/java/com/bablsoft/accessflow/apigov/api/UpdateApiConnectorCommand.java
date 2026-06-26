package com.bablsoft.accessflow.apigov.api;

import java.util.Map;
import java.util.UUID;

/**
 * Update command for an API connector. {@code credentials} is null when the caller leaves the stored
 * secret unchanged; a non-null (possibly empty) map replaces it.
 */
public record UpdateApiConnectorCommand(
        String name,
        String baseUrl,
        Map<String, String> defaultHeaders,
        Integer timeoutMs,
        Boolean tlsVerify,
        ApiAuthMethod authMethod,
        Map<String, String> credentials,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean textToApiEnabled,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        Long maxResponseBytes,
        Boolean active) {
}
