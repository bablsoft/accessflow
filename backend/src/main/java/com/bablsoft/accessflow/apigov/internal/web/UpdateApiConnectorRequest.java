package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.Oauth2ClientAuth;
import com.bablsoft.accessflow.apigov.api.Oauth2GrantType;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record UpdateApiConnectorRequest(
        @Size(min = 3, max = 255, message = "{validation.api_connector.name.size}")
        String name,
        @Size(max = 2048, message = "{validation.api_connector.base_url.size}")
        String baseUrl,
        Map<String, String> defaultHeaders,
        Map<String, String> traceHeaderMapping,
        @Min(value = 1, message = "{validation.api_connector.timeout.min}")
        Integer timeoutMs,
        Boolean tlsVerify,
        ApiAuthMethod authMethod,
        Map<String, String> credentials,
        @Size(max = 2048, message = "{validation.api_connector.oauth2_token_uri.size}")
        String oauth2TokenUri,
        @Size(max = 512, message = "{validation.api_connector.oauth2_client_id.size}")
        String oauth2ClientId,
        @Size(max = 1024, message = "{validation.api_connector.oauth2_client_secret.size}")
        String oauth2ClientSecret,
        @Size(max = 1024, message = "{validation.api_connector.oauth2_scopes.size}")
        String oauth2Scopes,
        @Size(max = 512, message = "{validation.api_connector.oauth2_audience.size}")
        String oauth2Audience,
        @Size(max = 4096, message = "{validation.api_connector.oauth2_refresh_token.size}")
        String oauth2RefreshToken,
        @Size(max = 255, message = "{validation.api_connector.oauth2_username.size}")
        String oauth2Username,
        @Size(max = 1024, message = "{validation.api_connector.oauth2_password.size}")
        String oauth2Password,
        Oauth2GrantType oauth2GrantType,
        Oauth2ClientAuth oauth2ClientAuth,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean textToApiEnabled,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        @Min(value = 1, message = "{validation.api_connector.max_response_bytes.min}")
        Long maxResponseBytes,
        Boolean active) {

    UpdateApiConnectorCommand toCommand() {
        return new UpdateApiConnectorCommand(name, baseUrl, defaultHeaders, traceHeaderMapping, timeoutMs,
                tlsVerify, authMethod, credentials, oauth2TokenUri, oauth2ClientId, oauth2ClientSecret,
                oauth2Scopes, oauth2Audience, oauth2RefreshToken, oauth2Username, oauth2Password,
                oauth2GrantType, oauth2ClientAuth, reviewPlanId, aiAnalysisEnabled, aiConfigId,
                textToApiEnabled, requireReviewReads, requireReviewWrites, maxResponseBytes, active);
    }
}
