package com.bablsoft.accessflow.apigov.api;

import java.util.Map;
import java.util.UUID;

/**
 * Create command for an API connector. {@code credentials} holds the raw auth secret fields keyed by
 * method (e.g. {@code api-key}, {@code header-name}, {@code token}, {@code username}/{@code password},
 * {@code client-id}/{@code client-secret}/{@code token-url}/{@code scopes}, {@code client-cert}/
 * {@code client-key}); the implementation serializes and AES-256-GCM encrypts it. Never persisted in
 * the clear, never echoed back.
 */
public record CreateApiConnectorCommand(
        UUID organizationId,
        String name,
        ApiProtocol protocol,
        String baseUrl,
        Map<String, String> defaultHeaders,
        Integer timeoutMs,
        Boolean tlsVerify,
        ApiAuthMethod authMethod,
        Map<String, String> credentials,
        String oauth2TokenUri,
        String oauth2ClientId,
        String oauth2ClientSecret,
        String oauth2Scopes,
        String oauth2Audience,
        String oauth2RefreshToken,
        String oauth2Username,
        String oauth2Password,
        Oauth2GrantType oauth2GrantType,
        Oauth2ClientAuth oauth2ClientAuth,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean textToApiEnabled,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        Long maxResponseBytes) {
}
