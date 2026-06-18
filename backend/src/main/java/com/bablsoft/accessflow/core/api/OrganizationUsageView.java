package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Current per-org resource usage against the configured quotas (AF-456). A {@code null} limit means
 * the dimension is unlimited.
 */
public record OrganizationUsageView(
        UUID organizationId,
        long datasourceCount,
        Integer maxDatasources,
        long userCount,
        Integer maxUsers,
        long queriesLast24h,
        Integer maxQueriesPerDay
) {}
