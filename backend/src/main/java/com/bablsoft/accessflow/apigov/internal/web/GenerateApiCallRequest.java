package com.bablsoft.accessflow.apigov.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GenerateApiCallRequest(
        @NotNull(message = "{validation.api_request.connector.required}")
        UUID connectorId,
        @NotBlank(message = "{validation.api_request.prompt.required}")
        String prompt,
        String language) {
}
