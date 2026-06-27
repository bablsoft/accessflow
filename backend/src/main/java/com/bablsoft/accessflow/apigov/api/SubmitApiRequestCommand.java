package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Command to submit a governed API call. {@code operationId} is null for a free-form call.
 * {@code submissionReason} carries {@code EMERGENCY_ACCESS} for break-glass.
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
        String requestBody,
        String justification,
        Instant scheduledFor,
        SubmissionReason submissionReason,
        String submittedIp,
        String submittedUserAgent) {
}
