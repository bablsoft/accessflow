package com.bablsoft.accessflow.apigov.api;

import java.util.Map;
import java.util.UUID;

/**
 * Update command for an API connector. {@code credentials} is null when the caller leaves the stored
 * secret unchanged; a non-null (possibly empty) map replaces it. Likewise each OAuth2 secret
 * ({@code oauth2ClientSecret}, {@code oauth2RefreshToken}, {@code oauth2Password}) is null to leave
 * the stored value unchanged. {@code reviewPlanId} follows the same null-means-unchanged rule, so
 * unassigning a plan goes through {@code clearReviewPlan} (mirrors the datasource update's
 * {@code clearAiConfig} convention); when true it wins over {@code reviewPlanId}.
 */
public record UpdateApiConnectorCommand(
        String name,
        String baseUrl,
        Map<String, String> defaultHeaders,
        Map<String, String> traceHeaderMapping,
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
        Boolean clearReviewPlan,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean textToApiEnabled,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        Long maxResponseBytes,
        Boolean active) {
}
