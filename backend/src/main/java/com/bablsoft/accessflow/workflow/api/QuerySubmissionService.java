package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.ClientApplication;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates submission of a new query: validates the SQL and the caller's permissions on the
 * target datasource, persists a {@code query_requests} row in {@link QueryStatus#PENDING_AI}, and
 * publishes a {@code QuerySubmittedEvent} so the AI module can analyze the query asynchronously.
 */
public interface QuerySubmissionService {

    QuerySubmissionResult submit(SubmissionInput input);

    record SubmissionInput(
            UUID datasourceId,
            String sql,
            String justification,
            UUID submitterUserId,
            UUID organizationId,
            boolean isAdmin,
            Instant scheduledFor,
            SubmissionReason submissionReason,
            String submittedIp,
            String submittedUserAgent,
            boolean ciCdOrigin,
            String recurrenceRule,
            Instant recurrenceUntil,
            /** The human an API-key caller acts for (#874); null for a human submission. */
            UUID onBehalfOfUserId,
            /** The calling application (#938); null when unknown. */
            ClientApplication application) {

        /** Backward-compatible constructor without the #938 calling application. */
        public SubmissionInput(UUID datasourceId, String sql, String justification,
                               UUID submitterUserId, UUID organizationId, boolean isAdmin,
                               Instant scheduledFor, SubmissionReason submissionReason,
                               String submittedIp, String submittedUserAgent, boolean ciCdOrigin,
                               String recurrenceRule, Instant recurrenceUntil,
                               UUID onBehalfOfUserId) {
            this(datasourceId, sql, justification, submitterUserId, organizationId, isAdmin,
                    scheduledFor, submissionReason, submittedIp, submittedUserAgent, ciCdOrigin,
                    recurrenceRule, recurrenceUntil, onBehalfOfUserId, null);
        }

        /** Backward-compatible constructor without the #874 on-behalf-of principal. */
        public SubmissionInput(UUID datasourceId, String sql, String justification,
                               UUID submitterUserId, UUID organizationId, boolean isAdmin,
                               Instant scheduledFor, SubmissionReason submissionReason,
                               String submittedIp, String submittedUserAgent, boolean ciCdOrigin,
                               String recurrenceRule, Instant recurrenceUntil) {
            this(datasourceId, sql, justification, submitterUserId, organizationId, isAdmin,
                    scheduledFor, submissionReason, submittedIp, submittedUserAgent, ciCdOrigin,
                    recurrenceRule, recurrenceUntil, null);
        }

        /** Backward-compatible constructor without the #627 recurrence fields. */
        public SubmissionInput(UUID datasourceId, String sql, String justification,
                               UUID submitterUserId, UUID organizationId, boolean isAdmin,
                               Instant scheduledFor, SubmissionReason submissionReason,
                               String submittedIp, String submittedUserAgent, boolean ciCdOrigin) {
            this(datasourceId, sql, justification, submitterUserId, organizationId, isAdmin,
                    scheduledFor, submissionReason, submittedIp, submittedUserAgent, ciCdOrigin,
                    null, null, (UUID) null);
        }
    }

    record QuerySubmissionResult(UUID id, QueryStatus status) {
    }
}
