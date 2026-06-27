package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.SubmitApiRequestCommand;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SubmitApiRequestRequest(
        @NotNull(message = "{validation.api_request.connector.required}")
        UUID connectorId,
        String operationId,
        @NotBlank(message = "{validation.api_request.verb.required}")
        String verb,
        @NotBlank(message = "{validation.api_request.path.required}")
        String requestPath,
        Map<String, String> requestHeaders,
        String requestBody,
        String justification,
        Instant scheduledFor,
        SubmissionReason submissionReason) {

    SubmitApiRequestCommand toCommand(UUID organizationId, UUID userId, boolean admin, String ip,
                                      String userAgent) {
        return new SubmitApiRequestCommand(connectorId, organizationId, userId, admin, operationId, verb,
                requestPath, requestHeaders, requestBody, justification, scheduledFor, submissionReason,
                ip, userAgent);
    }
}
