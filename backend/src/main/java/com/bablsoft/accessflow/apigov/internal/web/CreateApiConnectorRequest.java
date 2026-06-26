package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record CreateApiConnectorRequest(
        @NotBlank(message = "{validation.api_connector.name.required}")
        @Size(min = 3, max = 255, message = "{validation.api_connector.name.size}")
        String name,
        @NotNull(message = "{validation.api_connector.protocol.required}")
        ApiProtocol protocol,
        @NotBlank(message = "{validation.api_connector.base_url.required}")
        @Size(max = 2048, message = "{validation.api_connector.base_url.size}")
        String baseUrl,
        Map<String, String> defaultHeaders,
        @Min(value = 1, message = "{validation.api_connector.timeout.min}")
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
        @Min(value = 1, message = "{validation.api_connector.max_response_bytes.min}")
        Long maxResponseBytes) {

    CreateApiConnectorCommand toCommand(UUID organizationId) {
        return new CreateApiConnectorCommand(organizationId, name, protocol, baseUrl, defaultHeaders,
                timeoutMs, tlsVerify, authMethod, credentials, reviewPlanId, aiAnalysisEnabled,
                aiConfigId, textToApiEnabled, requireReviewReads, requireReviewWrites, maxResponseBytes);
    }
}
