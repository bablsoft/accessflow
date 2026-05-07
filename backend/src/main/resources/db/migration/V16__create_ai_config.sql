CREATE TABLE ai_config (
    id                       UUID         PRIMARY KEY,
    organization_id          UUID         NOT NULL UNIQUE REFERENCES organizations(id),
    provider                 ai_provider  NOT NULL,
    model                    VARCHAR(100) NOT NULL,
    endpoint                 VARCHAR(500),
    api_key_encrypted        TEXT,
    timeout_ms               INTEGER      NOT NULL DEFAULT 30000 CHECK (timeout_ms BETWEEN 1000 AND 600000),
    max_prompt_tokens        INTEGER      NOT NULL DEFAULT 8000  CHECK (max_prompt_tokens BETWEEN 100 AND 200000),
    max_completion_tokens    INTEGER      NOT NULL DEFAULT 2000  CHECK (max_completion_tokens BETWEEN 100 AND 200000),
    enable_ai_default        BOOLEAN      NOT NULL DEFAULT TRUE,
    auto_approve_low         BOOLEAN      NOT NULL DEFAULT FALSE,
    block_critical           BOOLEAN      NOT NULL DEFAULT TRUE,
    include_schema           BOOLEAN      NOT NULL DEFAULT TRUE,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
