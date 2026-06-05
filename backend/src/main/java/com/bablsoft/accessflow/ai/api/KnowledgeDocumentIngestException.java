package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when ingesting a RAG knowledge-base document fails — the embedding provider or vector store
 * rejected the request or was unreachable (AF-336). Resolved by the global handler to HTTP 502; the
 * underlying cause is logged, not surfaced in the response detail.
 */
public class KnowledgeDocumentIngestException extends RuntimeException {

    public KnowledgeDocumentIngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
