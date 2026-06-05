package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when an {@code ai_config}'s RAG settings are inconsistent (AF-336) — e.g. RAG enabled
 * without a store type or embedding model, an ANTHROPIC embedding provider (no embeddings API), or
 * a QDRANT backend missing its endpoint / collection. The {@code messageKey} selects the localized
 * {@code messages.properties} detail; resolved by the global handler to HTTP 400.
 */
public class AiConfigRagInvalidException extends RuntimeException {

    private final String messageKey;

    public AiConfigRagInvalidException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
