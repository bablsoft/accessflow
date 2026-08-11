package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record SubmitQueryCommand(
        UUID datasourceId,
        UUID submittedByUserId,
        String sqlText,
        QueryType queryType,
        boolean transactional,
        String justification,
        Instant scheduledFor,
        SubmissionReason submissionReason,
        String submittedIp,
        String submittedUserAgent,
        boolean ciCdOrigin,
        String recurrenceRule,
        Instant recurrenceUntil,
        Instant recurrenceNextRunAt) {

    /** Backward-compatible constructor without the #627 recurrence fields (defaults to absent). */
    public SubmitQueryCommand(UUID datasourceId, UUID submittedByUserId, String sqlText,
                              QueryType queryType, boolean transactional, String justification,
                              Instant scheduledFor, SubmissionReason submissionReason,
                              String submittedIp, String submittedUserAgent, boolean ciCdOrigin) {
        this(datasourceId, submittedByUserId, sqlText, queryType, transactional, justification,
                scheduledFor, submissionReason, submittedIp, submittedUserAgent, ciCdOrigin,
                null, null, null);
    }
}
