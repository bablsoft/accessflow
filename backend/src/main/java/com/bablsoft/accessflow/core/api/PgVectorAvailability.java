package com.bablsoft.accessflow.core.api;

/**
 * Whether the PostgreSQL {@code vector} (pgvector) extension is usable on this deployment, decided
 * once at startup while Flyway runs (AF-336). The in-app {@code PGVECTOR} RAG store needs the
 * extension; when it is absent the application still starts but that store is disabled — modules
 * read this flag to fail RAG-PGVECTOR operations cleanly instead of hitting a missing table. The
 * external {@code QDRANT} store does not depend on it.
 */
public interface PgVectorAvailability {

    /** {@code true} when the {@code vector} extension is installed and the {@code vector_store} backend is usable. */
    boolean isAvailable();
}
