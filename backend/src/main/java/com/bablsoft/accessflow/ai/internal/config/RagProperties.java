package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-wide RAG tunables (AF-336). Per-AI-config settings (store type, top-K, threshold,
 * embedding model) live on the {@code ai_config} row; these only cover the in-app pgvector embedding
 * dimension, the chunking sizes used at ingestion, and the per-document content cap.
 *
 * <p>{@code pgvectorDimensions} must match the {@code vector(N)} column created by Flyway V69 (via
 * the {@code rag_pgvector_dimensions} placeholder) — both default to 1536 and are driven by
 * {@code ACCESSFLOW_RAG_PGVECTOR_DIMENSIONS}.
 */
@ConfigurationProperties("accessflow.rag")
public record RagProperties(
        Integer pgvectorDimensions,
        Integer chunkSize,
        Integer maxDocumentChars) {

    public RagProperties {
        if (pgvectorDimensions == null || pgvectorDimensions < 1) {
            pgvectorDimensions = 1536;
        }
        if (chunkSize == null || chunkSize < 1) {
            chunkSize = 800;
        }
        if (maxDocumentChars == null || maxDocumentChars < 1) {
            maxDocumentChars = 100_000;
        }
    }
}
