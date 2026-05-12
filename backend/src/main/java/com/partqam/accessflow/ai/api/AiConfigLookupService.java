package com.partqam.accessflow.ai.api;

import java.util.UUID;

/**
 * Read-only API exposed to other modules (notably {@code api}) for cross-module visibility into
 * AI configuration state without crossing into {@code ai.internal}.
 */
public interface AiConfigLookupService {

    /**
     * @return {@code true} if the organization has at least one {@code ai_config} row that is
     * usable on its own — either {@code provider = OLLAMA} (keyless) or a non-blank API key is
     * stored. Does <strong>not</strong> require any datasource to bind to the config; admins
     * configure AI before creating their first datasource.
     */
    boolean hasAnyUsableAiConfig(UUID organizationId);
}
