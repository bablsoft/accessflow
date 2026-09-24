package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter parameters for {@link QueryRequestLookupService#findForOrganization}. All fields are
 * optional except {@code organizationId}; non-null fields are AND-combined.
 * {@code applicationName} (#938) is an exact match on the recorded calling application.
 */
public record QueryListFilter(
        UUID organizationId,
        UUID submittedByUserId,
        UUID datasourceId,
        QueryStatus status,
        QueryType queryType,
        Instant from,
        Instant to,
        String applicationName) {

    /** Backward-compatible constructor without the #938 application filter. */
    public QueryListFilter(UUID organizationId, UUID submittedByUserId, UUID datasourceId,
                           QueryStatus status, QueryType queryType, Instant from, Instant to) {
        this(organizationId, submittedByUserId, datasourceId, status, queryType, from, to, null);
    }
}
