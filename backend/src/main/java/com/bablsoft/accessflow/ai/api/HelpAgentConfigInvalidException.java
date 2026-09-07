package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when help-agent settings are out of range, or when enabling the agent would produce a
 * configuration that cannot answer — no bound AI configuration, retrieval requested against a
 * configuration whose RAG is off or whose embedding provider has no embeddings API, or an in-app
 * pgvector store that is disabled, missing its extension, or dimensioned differently from the
 * embedding model. The {@code messageKey} selects the localized {@code messages.properties} detail
 * — one key per cause, so the admin is told which of the three pgvector states to fix. Resolved by
 * the AI module's handler to HTTP 400.
 */
public class HelpAgentConfigInvalidException extends RuntimeException {

    private final String messageKey;

    public HelpAgentConfigInvalidException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
