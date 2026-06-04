-- AF-333: per-organization Langfuse integration settings (one row per org, like saml_config /
-- oauth2_config). Drives both LLM-call tracing (ingestion API) and runtime prompt management.
-- The secret key is AES-256-GCM encrypted before persistence and never returned in API responses.
CREATE TABLE langfuse_config (
    id                        UUID         PRIMARY KEY,
    organization_id           UUID         NOT NULL UNIQUE REFERENCES organizations(id),
    enabled                   BOOLEAN      NOT NULL DEFAULT FALSE,
    host                      VARCHAR(500),
    public_key                VARCHAR(255),
    secret_key_encrypted      TEXT,
    tracing_enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    prompt_management_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    version                   BIGINT       NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
