package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.QuerySubmitterEvidence;

import java.time.Instant;

/**
 * Usage evidence attached to a privileged-access row (#968): every query the user submitted in
 * the organization, and the break-glass ({@code EMERGENCY_ACCESS}) subset. Timestamps are null
 * when the matching count is zero.
 */
public record PrivilegedAccessEvidence(
        long submittedQueryCount,
        Instant lastSubmittedAt,
        long breakGlassExecutionCount,
        Instant lastBreakGlassAt
) {
    public static PrivilegedAccessEvidence none() {
        return new PrivilegedAccessEvidence(0L, null, 0L, null);
    }

    public static PrivilegedAccessEvidence from(QuerySubmitterEvidence evidence) {
        if (evidence == null) {
            return none();
        }
        return new PrivilegedAccessEvidence(evidence.submittedQueryCount(), evidence.lastSubmittedAt(),
                evidence.breakGlassExecutionCount(), evidence.lastBreakGlassAt());
    }
}
