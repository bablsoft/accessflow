package com.partqam.accessflow.ai.api;

import java.util.UUID;

/**
 * Higher-level orchestration entry point for AI analysis. Two paths:
 * <ul>
 *     <li>{@link #analyzePreview} — synchronous, used by the editor preview endpoint. No
 *         persistence; failures propagate as exceptions to the caller.</li>
 *     <li>{@link #analyzeSubmittedQuery} — fired from the {@code QuerySubmittedEvent} listener.
 *         Persists the result (or a sentinel failure row) and emits completion / failure events.
 *         Never throws.</li>
 * </ul>
 */
public interface AiAnalyzerService {

    AiAnalysisResult analyzePreview(UUID datasourceId, String sql, UUID userId,
                                    UUID organizationId, boolean isAdmin);

    void analyzeSubmittedQuery(UUID queryRequestId);
}
