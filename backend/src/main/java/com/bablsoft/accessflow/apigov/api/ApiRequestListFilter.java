package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter parameters for {@link ApiRequestService#list}. Only {@code organizationId} is required;
 * non-null fields are AND-combined. {@code submittedByUserId} is set for the self-scoped (non-admin)
 * listing and left {@code null} for the admin-wide listing.
 */
public record ApiRequestListFilter(
        UUID organizationId,
        UUID submittedByUserId,
        UUID connectorId,
        QueryStatus status,
        String verb,
        String traceId,
        String spanId,
        Instant from,
        Instant to) {
}
