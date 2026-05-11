package com.partqam.accessflow.ai.api;

import java.util.UUID;

/**
 * Read-only API exposed to other modules (notably {@code api}) for cross-module visibility into
 * AI configuration state without crossing into {@code ai.internal}.
 */
public interface AiConfigLookupService {

    /**
     * @return {@code true} if the organization has at least one active datasource with
     * {@code ai_analysis_enabled = true} bound to an {@code ai_config} that is usable —
     * either {@code provider = OLLAMA} or a non-blank API key is stored.
     */
    boolean hasUsableAiAnalysisConfiguredDatasource(UUID organizationId);
}
