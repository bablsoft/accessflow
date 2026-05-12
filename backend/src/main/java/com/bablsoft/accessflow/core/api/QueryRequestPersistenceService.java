package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Persists a new {@code query_requests} row in {@link QueryStatus#PENDING_AI}. Used by the workflow
 * module to submit queries without reaching into {@code core/internal} JPA entities.
 */
public interface QueryRequestPersistenceService {

    UUID submit(SubmitQueryCommand command);
}
