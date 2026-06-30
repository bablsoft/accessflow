package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiConnectorView;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.Oauth2ClientAuth;
import com.bablsoft.accessflow.apigov.api.Oauth2GrantType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ApiConnectorResponse(
        UUID id,
        String name,
        ApiProtocol protocol,
        String baseUrl,
        Map<String, String> defaultHeaders,
        Map<String, String> traceHeaderMapping,
        int timeoutMs,
        boolean tlsVerify,
        ApiAuthMethod authMethod,
        boolean hasCredentials,
        String oauth2TokenUri,
        String oauth2ClientId,
        String oauth2Scopes,
        String oauth2Audience,
        String oauth2Username,
        Oauth2GrantType oauth2GrantType,
        Oauth2ClientAuth oauth2ClientAuth,
        boolean oauth2ClientSecretConfigured,
        boolean oauth2RefreshTokenConfigured,
        boolean oauth2PasswordConfigured,
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
                v.traceHeaderMapping(), v.timeoutMs(), v.tlsVerify(), v.authMethod(), v.hasCredentials(),
                v.oauth2TokenUri(), v.oauth2ClientId(), v.oauth2Scopes(), v.oauth2Audience(),
                v.oauth2Username(), v.oauth2GrantType(), v.oauth2ClientAuth(),
                v.oauth2ClientSecretConfigured(), v.oauth2RefreshTokenConfigured(),
                v.oauth2PasswordConfigured(), v.reviewPlanId(),
                v.aiAnalysisEnabled(), v.aiConfigId(), v.textToApiEnabled(), v.requireReviewReads(),
                v.requireReviewWrites(), v.maxResponseBytes(), v.active(), v.schemaPresent(), v.createdAt());
    }
}
