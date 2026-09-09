package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when an {@code ai_config}'s RAG settings are inconsistent (AF-336) — e.g. RAG enabled
 * without a store type or embedding model, an embedding provider that publishes no embeddings API
 * (Anthropic), a QDRANT backend missing its endpoint / collection, or a vector length the provider
 * or the pgvector column cannot produce (AF-918). The {@code messageKey} selects the localized
 * {@code messages.properties} detail and {@code args} fills its placeholders; resolved by the module
 * handler to HTTP 400.
 */
public class AiConfigRagInvalidException extends RuntimeException {

    private final String messageKey;
    private final Object[] args;

    public AiConfigRagInvalidException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public String messageKey() {
        return messageKey;
    }

    /** Placeholder values for the localized message, in declaration order. */
    public Object[] args() {
        return args.clone();
    }
}
