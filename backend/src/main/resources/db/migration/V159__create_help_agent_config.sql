-- AF-901 (epic AF-899): per-organization settings for the in-app documentation help chat agent.
--
-- One settings row per organization (a singleton, like langfuse_config / scim_config) binding the
-- agent to the ai_config whose chat model answers questions, plus the retrieval / conversation /
-- retention tunables and the corpus-ingestion state written by the indexer.
--
-- ai_config_id is deliberately ON DELETE SET NULL: losing the bound configuration must disable help
-- chat, never block an admin from deleting an ai_config. Help therefore does not join the
-- AI_CONFIG_IN_USE guard that datasource bindings do.
--
-- retrieval_enabled=false is a supported steady state, not a degraded one — the agent falls back to
-- the generated quick-reference block, which is the only mode available to an install whose
-- embedding provider cannot embed (Anthropic ships no embeddings API).
--
-- No new enum type and no role_permissions seed: AI_MANAGE already covers "provider configs,
-- analyses history, knowledge base, Langfuse, RAG".

CREATE TABLE help_agent_config (
    id                           UUID             PRIMARY KEY,
    organization_id              UUID             NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    enabled                      BOOLEAN          NOT NULL DEFAULT false,
    ai_config_id                 UUID             REFERENCES ai_config(id) ON DELETE SET NULL,
    retrieval_enabled            BOOLEAN          NOT NULL DEFAULT true,
    top_k                        INTEGER          NOT NULL DEFAULT 6,
    similarity_threshold         DOUBLE PRECISION NOT NULL DEFAULT 0.4,
    max_history_turns            INTEGER          NOT NULL DEFAULT 8,
    max_question_chars           INTEGER          NOT NULL DEFAULT 2000,
    send_user_context            BOOLEAN          NOT NULL DEFAULT true,
    retention_days               INTEGER          NOT NULL DEFAULT 90,
    per_user_requests_per_minute INTEGER          NOT NULL DEFAULT 6,
    indexed_corpus_version       VARCHAR(64),
    indexed_at                   TIMESTAMPTZ,
    index_error                  TEXT,
    version                      BIGINT           NOT NULL DEFAULT 0,
    created_at                   TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX help_agent_config_ai_config_id_idx ON help_agent_config(ai_config_id);
