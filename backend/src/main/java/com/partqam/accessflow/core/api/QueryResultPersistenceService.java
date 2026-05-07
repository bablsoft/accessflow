package com.partqam.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module write/read access to {@code query_request_results} — the JSONB snapshot of the
 * last SELECT execution for a given query request. Writers (the workflow module's lifecycle
 * service) overwrite any existing snapshot on re-execute; readers paginate over the stored rows.
 */
public interface QueryResultPersistenceService {

    /** Inserts or replaces the results row for {@code queryRequestId}. */
    void save(SaveResultCommand command);

    /** Returns the stored snapshot or empty if no results exist. */
    Optional<QueryResultSnapshot> find(UUID queryRequestId);

    record SaveResultCommand(
            UUID queryRequestId,
            String columnsJson,
            String rowsJson,
            long rowCount,
            boolean truncated,
            int durationMs) {
    }

    record QueryResultSnapshot(
            UUID queryRequestId,
            String columnsJson,
            String rowsJson,
            long rowCount,
            boolean truncated,
            int durationMs) {
    }
}
