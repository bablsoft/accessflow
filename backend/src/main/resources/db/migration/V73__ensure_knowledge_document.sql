-- AF-336: ensure the knowledge_document table exists independently of pgvector availability.
--
-- V69 creates BOTH knowledge_document (no pgvector dependency) and vector_store (a VECTOR(N)
-- column + HNSW index that require the `vector` extension) in one file. When the extension is
-- unavailable, PgVectorFlywayConfiguration's migration strategy skips V69 entirely so startup does
-- not fail — but knowledge_document is a JPA entity and Hibernate (ddl-auto=validate) needs its
-- table to exist. This migration recreates that table idempotently: a no-op when V69 already ran,
-- the creator in the degraded (pgvector-absent) path. The definition is intentionally duplicated
-- from V69 (kept in sync by hand) so the two paths produce an identical schema.

CREATE TABLE IF NOT EXISTS knowledge_document (
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

CREATE INDEX IF NOT EXISTS knowledge_document_ai_config_id_idx ON knowledge_document(ai_config_id);
