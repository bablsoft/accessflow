package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Persists pre-flight cost estimates (issue AF-624) into {@code query_estimates} and links the row
 * from {@code query_requests.query_estimate_id}, mirroring {@link AiAnalysisPersistenceService}.
 */
public interface QueryEstimatePersistenceService {

    /**
     * Inserts the estimate row and sets the {@code query_requests.query_estimate_id} back-pointer
     * in the same transaction. When a row already exists for the query request (a concurrent
     * trigger raced this one) the existing row's id is returned and nothing is written.
     *
     * @return the persisted (or pre-existing) estimate row id
     */
    UUID persist(UUID queryRequestId, PersistQueryEstimateCommand command);
}
