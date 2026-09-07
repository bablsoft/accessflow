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

    /**
     * The resolved state, including <em>why</em> the store is unusable — the extension is missing
     * versus the operator disabled pgvector. Callers that report a fix to an admin need the reason
     * (AF-901); callers that only gate behaviour use {@link #isAvailable()}.
     */
    PgVectorStatus status();
}
