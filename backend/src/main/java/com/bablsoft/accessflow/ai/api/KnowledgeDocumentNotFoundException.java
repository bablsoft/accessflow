package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Thrown when a RAG knowledge-base document cannot be found within the given AI configuration and
 * organization (AF-336). Resolved by the global handler to HTTP 404.
 */
public class KnowledgeDocumentNotFoundException extends RuntimeException {

    public KnowledgeDocumentNotFoundException(UUID id) {
        super("Knowledge document not found: " + id);
    }
}
