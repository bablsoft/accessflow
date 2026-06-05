-- AF-336: RAG knowledge base storage.
--
-- knowledge_document is the admin-managed source of truth — the raw documents attached to an
-- ai_config. On ingestion each document is chunked, embedded and upserted into the configured
-- vector store; chunk_count / status record the outcome. Deleting a document removes its chunks.
--
-- vector_store is Spring AI's PgVectorStore table for the in-app (PGVECTOR) backend, created here
-- via Flyway (PgVectorStore is built with initializeSchema=false) so all schema changes stay under
-- Flyway with ddl-auto=validate. The `vector` extension itself is NOT created here — it is not a
-- trusted extension and the application DB role is not a superuser, so it is provisioned by a
-- superuser init script (deploy/postgres-init/02-pgvector.sql in Docker/Helm, withInitScript in
-- Testcontainers). The embedding dimension is a Flyway placeholder so operators whose embedding
-- model emits a different dimension set ACCESSFLOW_RAG_PGVECTOR_DIMENSIONS before the first migrate.
-- Rows are partitioned by ai_config_id in the metadata JSON for filtered search / delete.

CREATE TABLE knowledge_document (
    id              UUID         PRIMARY KEY,
    ai_config_id    UUID         NOT NULL REFERENCES ai_config(id) ON DELETE CASCADE,
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    title           VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    char_count      INTEGER      NOT NULL,
    chunk_count     INTEGER      NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'INDEXED',
    error_message   TEXT,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX knowledge_document_ai_config_id_idx ON knowledge_document(ai_config_id);

-- Spring AI PgVectorStore schema (initializeSchema=false). gen_random_uuid() is built into
-- PostgreSQL 13+, so no uuid-ossp extension is required.
CREATE TABLE vector_store (
    id        UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(${rag_pgvector_dimensions})
);

CREATE INDEX vector_store_embedding_idx ON vector_store
    USING HNSW (embedding vector_cosine_ops);
