package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.RagStoreType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Builds a Spring AI {@link VectorStore} for an {@code ai_config}'s RAG backend (AF-336). Both
 * backends go through the same {@link VectorStore} abstraction wired with the per-config
 * {@link EmbeddingModel}, so ingestion ({@code add}) and retrieval ({@code similaritySearch}) are
 * backend-agnostic. The matching Spring AI auto-configs are excluded — stores are built per row.
 */
interface VectorStoreFactory {

    /**
     * @param type           vector-store backend (PGVECTOR in-app, QDRANT external)
     * @param embeddingModel the per-config embedding model
     * @param dimensions     embedding dimension (PGVECTOR — must match the {@code vector(N)} column)
     * @param endpoint       external store endpoint (QDRANT); ignored for PGVECTOR
     * @param collection     external collection / index name (QDRANT); ignored for PGVECTOR
     * @param apiKey         external store API key (QDRANT, nullable); ignored for PGVECTOR
     */
    VectorStore create(RagStoreType type, EmbeddingModel embeddingModel, int dimensions,
                       String endpoint, String collection, String apiKey);
}
