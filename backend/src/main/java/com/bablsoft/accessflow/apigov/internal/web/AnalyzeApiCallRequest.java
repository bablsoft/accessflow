package com.bablsoft.accessflow.apigov.internal.web;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AnalyzeApiCallRequest(
        @NotNull(message = "{validation.api_request.connector.required}")
        UUID connectorId,
        String operationId,
        String verb,
        String requestPath,
        String requestBody,
        String language) {
}
