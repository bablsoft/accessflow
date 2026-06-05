package com.bablsoft.accessflow.ai.api;

/**
 * Inputs for adding a RAG knowledge-base document to an {@code ai_config} (AF-336). On ingestion the
 * {@code content} is chunked, embedded and upserted into the configured vector store.
 */
public record CreateKnowledgeDocumentCommand(
        String title,
        String content) {
}
