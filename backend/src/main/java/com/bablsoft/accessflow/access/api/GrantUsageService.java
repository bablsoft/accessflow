package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * Read side of least-privilege intelligence (#625): per-standing-grant usage evidence and the
 * revocation recommendation derived from it.
 *
 * <p>Reads a materialised summary refreshed by {@code GrantUsageAggregationJob}, so a grant created
 * since the last tick simply has no row yet. That is why {@link #findFor} returns an
 * {@link Optional} the caller must render as "no data" rather than defaulting to "never used" — the
 * two look identical in the data and mean opposite things to a reviewer.
 */
public interface GrantUsageService {

    /** Usage evidence for one standing grant, or empty when it has not been summarised yet. */
    Optional<GrantUsageView> findFor(UUID organizationId, GrantResourceKind resourceKind,
                                     UUID resourceId, UUID userId);

    /**
     * The over-provisioned access report: standing grants in the organization matching the filter.
     * Defaults to worst-first ordering (never-used, then longest-idle) when the page request carries
     * no sort.
     */
    PageResponse<GrantUsageView> report(UUID organizationId, GrantUsageReportQuery query,
                                        PageRequest pageRequest);
}
