-- AF-336: per-AI-config RAG (retrieval-augmented generation) knowledge base.
-- Admins enable RAG on an ai_config, pick a vector-store backend (in-app pgvector or external
-- Qdrant), and configure a dedicated embedding model (independent of the chat provider — an
-- Anthropic chat config can still embed via OpenAI / Ollama). At analysis / text-to-SQL time the
-- most relevant knowledge chunks are retrieved and injected into the prompt's {{rag_context}} token.
-- All columns are nullable / defaulted so the migration is zero-downtime and RAG stays opt-in.

CREATE TYPE rag_store_type AS ENUM ('PGVECTOR', 'QDRANT');

ALTER TABLE ai_config
    ADD COLUMN rag_enabled               BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN rag_store_type            rag_store_type,
    ADD COLUMN rag_top_k                 INTEGER          NOT NULL DEFAULT 4
        CHECK (rag_top_k BETWEEN 1 AND 20),
    ADD COLUMN rag_similarity_threshold  DOUBLE PRECISION NOT NULL DEFAULT 0.5
        CHECK (rag_similarity_threshold BETWEEN 0 AND 1),
    -- External store (QDRANT) connection. The secret is AES-256-GCM encrypted, never serialized.
    ADD COLUMN rag_endpoint              VARCHAR(500),
    ADD COLUMN rag_collection            VARCHAR(255),
    ADD COLUMN rag_api_key_encrypted     TEXT,
    -- Dedicated embedding model. embedding_provider reuses the ai_provider enum but ANTHROPIC is
    -- rejected at the service layer (no embeddings API). The key is AES-256-GCM encrypted.
    ADD COLUMN embedding_provider        ai_provider,
    ADD COLUMN embedding_model           VARCHAR(100),
    ADD COLUMN embedding_endpoint        VARCHAR(500),
    ADD COLUMN embedding_api_key_encrypted TEXT;
