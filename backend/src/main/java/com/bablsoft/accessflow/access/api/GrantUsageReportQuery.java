package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.GrantResourceKind;

import java.util.Set;
import java.util.UUID;

/**
 * Filters for the over-provisioned access report (#625). Every field is optional; an all-null query
 * lists every standing grant in the organization. Use {@link #empty()} rather than passing null.
 */
public record GrantUsageReportQuery(
        GrantResourceKind resourceKind,
        Set<GrantUsageRecommendation> recommendations,
        UUID resourceId,
        UUID userId) {

    public GrantUsageReportQuery {
        recommendations = recommendations == null || recommendations.isEmpty()
                ? Set.of()
                : Set.copyOf(recommendations);
    }

    public static GrantUsageReportQuery empty() {
        return new GrantUsageReportQuery(null, Set.of(), null, null);
    }
}
