package com.bablsoft.accessflow.core.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls how the startup migration strategy handles the PostgreSQL {@code vector} (pgvector)
 * extension required by the in-app RAG store (AF-336).
 *
 * <ul>
 *   <li>{@code enabled} (default {@code true}) — when {@code false} the deployment opts out of
 *       pgvector entirely: no provisioning is attempted, the {@code vector_store} migration is
 *       skipped and in-app PGVECTOR RAG is disabled regardless of whether the extension exists.</li>
 *   <li>{@code autoProvision} (default {@code true}) — best-effort {@code CREATE EXTENSION IF NOT
 *       EXISTS vector} before migrating, so a deployment whose DB role may create extensions does
 *       not need a separate superuser init step. Set {@code false} to leave provisioning entirely
 *       to the operator (the app still detects and uses an already-installed extension).</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.rag.pgvector")
public record PgVectorProperties(Boolean enabled, Boolean autoProvision) {

    public PgVectorProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (autoProvision == null) {
            autoProvision = true;
        }
    }
}
