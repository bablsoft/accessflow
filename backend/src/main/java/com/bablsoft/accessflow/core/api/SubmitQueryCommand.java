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
        Instant recurrenceNextRunAt,
        UUID onBehalfOfUserId,
        /** The calling application (#938); null when unknown. */
        ClientApplication application) {

    /** Backward-compatible constructor without the #938 calling application. */
    public SubmitQueryCommand(UUID datasourceId, UUID submittedByUserId, String sqlText,
                              QueryType queryType, boolean transactional, String justification,
                              Instant scheduledFor, SubmissionReason submissionReason,
                              String submittedIp, String submittedUserAgent, boolean ciCdOrigin,
                              String recurrenceRule, Instant recurrenceUntil,
                              Instant recurrenceNextRunAt, UUID onBehalfOfUserId) {
        this(datasourceId, submittedByUserId, sqlText, queryType, transactional, justification,
                scheduledFor, submissionReason, submittedIp, submittedUserAgent, ciCdOrigin,
                recurrenceRule, recurrenceUntil, recurrenceNextRunAt, onBehalfOfUserId, null);
    }

    /** Backward-compatible constructor without the #874 on-behalf-of principal. */
    public SubmitQueryCommand(UUID datasourceId, UUID submittedByUserId, String sqlText,
                              QueryType queryType, boolean transactional, String justification,
                              Instant scheduledFor, SubmissionReason submissionReason,
                              String submittedIp, String submittedUserAgent, boolean ciCdOrigin,
                              String recurrenceRule, Instant recurrenceUntil,
                              Instant recurrenceNextRunAt) {
        this(datasourceId, submittedByUserId, sqlText, queryType, transactional, justification,
                scheduledFor, submissionReason, submittedIp, submittedUserAgent, ciCdOrigin,
                recurrenceRule, recurrenceUntil, recurrenceNextRunAt, null);
    }

    /** Backward-compatible constructor without the #627 recurrence fields (defaults to absent). */
    public SubmitQueryCommand(UUID datasourceId, UUID submittedByUserId, String sqlText,
                              QueryType queryType, boolean transactional, String justification,
                              Instant scheduledFor, SubmissionReason submissionReason,
                              String submittedIp, String submittedUserAgent, boolean ciCdOrigin) {
        this(datasourceId, submittedByUserId, sqlText, queryType, transactional, justification,
                scheduledFor, submissionReason, submittedIp, submittedUserAgent, ciCdOrigin,
                null, null, null, (UUID) null);
    }
}
