package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter parameters for {@link QueryRequestLookupService#findForOrganization}. All fields are
 * optional except {@code organizationId}; non-null fields are AND-combined.
 */
public record QueryListFilter(
        UUID organizationId,
        UUID submittedByUserId,
        UUID datasourceId,
        QueryStatus status,
        QueryType queryType,
        Instant from,
        Instant to) {
}
