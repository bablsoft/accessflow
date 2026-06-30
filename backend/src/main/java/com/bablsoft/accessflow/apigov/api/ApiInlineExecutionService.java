package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/**
 * Executes an already-approved, inline API call against a connector — used by the {@code requestgroups}
 * ordered executor for API-call members, where the GROUP (not a persisted {@code api_requests} row)
 * owns the AI + review + approval lifecycle. The call runs through the same connector auth, default
 * headers, trace propagation, response cap, and per-user response-field masking as the persisted
 * {@code ApiRequestService.execute} path, but persists nothing of its own.
 */
public interface ApiInlineExecutionService {

    ApiInlineExecutionResult executeInline(ApiInlineExecutionCommand command);

    /**
     * Inline call definition. JSON map/list fields ({@code requestHeadersJson}, {@code queryParamsJson},
     * {@code formFieldsJson}) carry the same serialized shapes stored on a group item; {@code userId}
     * scopes the response-field masking.
     */
    record ApiInlineExecutionCommand(
            UUID connectorId,
            UUID organizationId,
            UUID userId,
            String operationId,
            String verb,
            String requestPath,
            String requestHeadersJson,
            String queryParamsJson,
            ApiBodyType bodyType,
            String requestContentType,
            String requestBody,
            String formFieldsJson,
            String binaryFilename) {
    }

    /**
     * Outcome of the inline call. {@code success} is false on a transport-level error
     * ({@code errorMessage} set) or an upstream HTTP status &ge; 400; the {@code requestgroups}
     * executor treats {@code success=false} as a member failure for stop-on-failure handling.
     */
    record ApiInlineExecutionResult(
            boolean success,
            int statusCode,
            Integer durationMs,
            Long responseBytes,
            boolean truncated,
            String responseSnapshot,
            String responseContentType,
            String errorMessage) {
    }
}
