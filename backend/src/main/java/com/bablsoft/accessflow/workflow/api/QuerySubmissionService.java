package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

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
            boolean isAdmin) {
    }

    record QuerySubmissionResult(UUID id, QueryStatus status) {
    }
}
