package com.bablsoft.accessflow.core.api;

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
        QueryStatus status) {
}
