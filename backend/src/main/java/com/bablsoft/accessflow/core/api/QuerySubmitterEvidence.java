package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One user's submission history in an organization, aggregated over {@code query_requests}
 * (#968): every row they submitted, and the {@link SubmissionReason#EMERGENCY_ACCESS} subset.
 *
 * <p>Counts cover every status — a rejected submission still exercised whatever let the user
 * submit it — and recurring occurrence rows, which are attributed to the series submitter.
 * Timestamps are {@code max(created_at)}; null when the count is zero.
 */
public record QuerySubmitterEvidence(
        UUID userId,
        long submittedQueryCount,
        Instant lastSubmittedAt,
        long breakGlassExecutionCount,
        Instant lastBreakGlassAt
) {
    /** The evidence for a user who has never submitted a query in the organization. */
    public static QuerySubmitterEvidence none(UUID userId) {
        return new QuerySubmitterEvidence(userId, 0L, null, 0L, null);
    }
}
