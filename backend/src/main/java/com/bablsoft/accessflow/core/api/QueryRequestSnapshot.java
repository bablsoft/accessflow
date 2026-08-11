package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module DTO carrying the fields of a query request that other modules need to read.
 * Lets modules outside {@code core} (e.g. {@code ai}) access query state without touching
 * {@code core/internal} JPA entities.
 */
public record QueryRequestSnapshot(
        UUID id,
        UUID datasourceId,
        UUID organizationId,
        UUID submittedByUserId,
        String sqlText,
        QueryType queryType,
        boolean transactional,
        QueryStatus status,
        Instant scheduledFor,
        String submittedIp,
        String submittedUserAgent,
        boolean ciCdOrigin,
        String recurrenceRule,
        Instant recurrenceUntil,
        Instant recurrenceNextRunAt,
        UUID recurringParentId) {

    /** Backward-compatible constructor without the #627 recurrence fields (defaults to absent). */
    public QueryRequestSnapshot(UUID id, UUID datasourceId, UUID organizationId,
                                UUID submittedByUserId, String sqlText, QueryType queryType,
                                boolean transactional, QueryStatus status, Instant scheduledFor,
                                String submittedIp, String submittedUserAgent, boolean ciCdOrigin) {
        this(id, datasourceId, organizationId, submittedByUserId, sqlText, queryType, transactional,
                status, scheduledFor, submittedIp, submittedUserAgent, ciCdOrigin,
                null, null, null, null);
    }
}
