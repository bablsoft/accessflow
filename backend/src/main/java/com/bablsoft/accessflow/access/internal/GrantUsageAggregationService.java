package com.bablsoft.accessflow.access.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Write side of least-privilege intelligence (#625). Public only so {@code internal.scheduled} can
 * see it; it stays off {@code access.api} deliberately, so the Modulith boundary keeps any other
 * module from moving the fold cursor. Only {@code GrantUsageAggregationJob} drives it.
 */
public interface GrantUsageAggregationService {

    /** Organization ids to aggregate this tick, oldest-id first and skipping disabled tenants. */
    List<UUID> findOrganizationIds();

    /**
     * Reconciles one organization's grant summaries against its live grants, folds new audit usage
     * into them, recomputes every recommendation, and emits any due staleness nudges.
     *
     * @return how many summary rows the organization now has
     */
    int aggregateOrganization(UUID organizationId, Instant now);
}
