package com.bablsoft.accessflow.ai.internal;

/**
 * Retrieves RAG knowledge-base context for an analysis / SQL-generation call (AF-336). Built per
 * {@code ai_config} row by the holder and passed into each provider delegate, which substitutes the
 * result into the prompt's {@code {{rag_context}}} token. Returning {@code null} / blank lets the
 * renderer apply its "no knowledge base context" fallback. Implementations must never throw — a
 * retrieval failure degrades gracefully to no context, never breaking analysis.
 */
@FunctionalInterface
interface RagRetriever {

    /** No-op retriever used when RAG is disabled for the config. */
    RagRetriever DISABLED = query -> null;

    String retrieve(String query);
}
