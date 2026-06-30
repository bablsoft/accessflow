package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command to submit a governed API call. {@code operationId} is null for a free-form call.
 * {@code submissionReason} carries {@code EMERGENCY_ACCESS} for break-glass. The body composition
 * ({@code bodyType} + {@code requestBody} / {@code formFields} / {@code binaryFilename}) and
 * {@code queryParams} mirror a Postman request.
 */
public record SubmitApiRequestCommand(
        UUID connectorId,
        UUID organizationId,
        UUID submitterUserId,
        boolean admin,
        String operationId,
        String verb,
        String requestPath,
        Map<String, String> requestHeaders,
        Map<String, String> queryParams,
        ApiBodyType bodyType,
        String requestContentType,
        String requestBody,
        List<ApiFormField> formFields,
        String binaryFilename,
        String justification,
        Instant scheduledFor,
        SubmissionReason submissionReason,
        String submittedIp,
        String submittedUserAgent) {
}
