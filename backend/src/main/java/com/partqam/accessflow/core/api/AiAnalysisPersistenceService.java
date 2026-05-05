package com.partqam.accessflow.core.api;

import java.util.UUID;

public interface AiAnalysisPersistenceService {

    /**
     * Persist an AI analysis row and link it to the originating query request in a single
     * transaction. Returns the new analysis row id.
     */
    UUID persist(UUID queryRequestId, PersistAiAnalysisCommand command);
}
