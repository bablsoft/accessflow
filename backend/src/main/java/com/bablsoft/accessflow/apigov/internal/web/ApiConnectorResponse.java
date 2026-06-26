package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiConnectorView;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ApiConnectorResponse(
        UUID id,
        String name,
        ApiProtocol protocol,
        String baseUrl,
        Map<String, String> defaultHeaders,
        int timeoutMs,
        boolean tlsVerify,
        ApiAuthMethod authMethod,
        boolean hasCredentials,
        UUID reviewPlanId,
        boolean aiAnalysisEnabled,
        UUID aiConfigId,
        boolean textToApiEnabled,
        boolean requireReviewReads,
        boolean requireReviewWrites,
        long maxResponseBytes,
        boolean active,
        boolean schemaPresent,
        Instant createdAt) {

    static ApiConnectorResponse from(ApiConnectorView v) {
        return new ApiConnectorResponse(v.id(), v.name(), v.protocol(), v.baseUrl(), v.defaultHeaders(),
                v.timeoutMs(), v.tlsVerify(), v.authMethod(), v.hasCredentials(), v.reviewPlanId(),
                v.aiAnalysisEnabled(), v.aiConfigId(), v.textToApiEnabled(), v.requireReviewReads(),
                v.requireReviewWrites(), v.maxResponseBytes(), v.active(), v.schemaPresent(), v.createdAt());
    }
}
