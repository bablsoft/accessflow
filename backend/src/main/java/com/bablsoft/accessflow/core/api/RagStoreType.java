package com.bablsoft.accessflow.core.api;

/**
 * Vector-store backend for an AI configuration's RAG knowledge base (AF-336).
 *
 * <ul>
 *   <li>{@code PGVECTOR} — in-app store backed by the shared PostgreSQL database with the
 *       {@code vector} extension. Self-contained; no external service.</li>
 *   <li>{@code QDRANT} — external Qdrant vector store, reached over the configured endpoint.</li>
 * </ul>
 */
public enum RagStoreType {
    PGVECTOR,
    QDRANT
}
