package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public interface AiAnalysisPersistenceService {

    /**
     * Persist an AI analysis row and link it to the originating query request in a single
     * transaction. Returns the new analysis row id.
     */
    UUID persist(UUID queryRequestId, PersistAiAnalysisCommand command);

    /**
     * Remove the AI analysis row currently linked to a query request, if any, and clear the
     * foreign key on the query so a follow-up {@link #persist} call can attach a fresh analysis.
     * Used when an admin / reviewer triggers a re-analysis on a query whose previous analysis
     * failed.
     */
    void deleteForQuery(UUID queryRequestId);
}
