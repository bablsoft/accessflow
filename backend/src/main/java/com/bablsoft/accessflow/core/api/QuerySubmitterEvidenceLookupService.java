package com.bablsoft.accessflow.core.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Per-submitter aggregate over an organization's {@code query_requests} (#968) — the usage
 * evidence behind the privileged-access report.
 *
 * <p>Read straight from {@code query_requests} at request time rather than from the #625
 * {@code grant_usage_summary}: that fold is keyed per <em>grant</em> and drops any event that
 * matches no live grant, which is exactly every query a {@code QUERY_ADMIN} holder submits with no
 * permission row. Org scope is applied through {@code datasources.organization_id}, as the other
 * query aggregations in this module do.
 */
public interface QuerySubmitterEvidenceLookupService {

    /**
     * Evidence for each of {@code userIds} that has submitted at least one query in the
     * organization. Users with no submissions are absent from the map — callers substitute
     * {@link QuerySubmitterEvidence#none}. An empty input yields an empty map without a query.
     */
    Map<UUID, QuerySubmitterEvidence> findBySubmitters(UUID organizationId, Collection<UUID> userIds);
}
