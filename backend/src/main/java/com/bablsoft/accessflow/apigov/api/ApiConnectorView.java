package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read view of an API connector. Never carries the auth secret — only {@code authMethod} and a
 * {@code hasCredentials} flag indicating whether secret material is stored.
 */
public record ApiConnectorView(
        UUID id,
        UUID organizationId,
        String name,
        ApiProtocol protocol,
        String baseUrl,
        Map<String, String> defaultHeaders,
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
}
