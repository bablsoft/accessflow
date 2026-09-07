package com.bablsoft.accessflow.core.api;

/**
 * Why the in-app pgvector RAG store is (un)usable on this deployment, decided once at startup while
 * Flyway runs. {@link PgVectorAvailability#isAvailable()} collapses this to a boolean; features that
 * ask an admin to *fix* the deployment need the reason, because the two unavailable states have
 * different remedies and neither is discoverable from the failure they eventually cause.
 */
public enum PgVectorStatus {

    /** The {@code vector} extension is installed and {@code vector_store} exists. */
    AVAILABLE,

    /**
     * The {@code vector} extension is not installed and could not be auto-provisioned, so the
     * {@code vector_store} migration was skipped. Remedy: install pgvector (a pgvector-enabled image
     * or {@code CREATE EXTENSION vector} as a superuser).
     */
    EXTENSION_MISSING,

    /**
     * The operator opted out with {@code ACCESSFLOW_RAG_PGVECTOR_ENABLED=false}, so no provisioning
     * was attempted and the {@code vector_store} migration was skipped regardless of whether the
     * extension exists. Remedy: re-enable the property, or use an external Qdrant store.
     */
    DISABLED
}
