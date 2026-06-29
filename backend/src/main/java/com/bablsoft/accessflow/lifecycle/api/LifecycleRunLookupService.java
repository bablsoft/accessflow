package com.bablsoft.accessflow.lifecycle.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cross-module read access to lifecycle runs for the compliance module's retention-adherence /
 * deletion-history report (AF-499). Other modules consume this rather than reaching into
 * {@code lifecycle.internal}.
 */
public interface LifecycleRunLookupService {

    /**
     * Lifecycle runs in {@code organizationId} created within the half-open {@code [from, to)}
     * window, newest first, optionally scoped to a single datasource, capped at {@code limit} rows.
     */
    List<LifecycleRunView> findForPeriod(UUID organizationId, Instant from, Instant to,
                                         UUID datasourceId, int limit);
}
