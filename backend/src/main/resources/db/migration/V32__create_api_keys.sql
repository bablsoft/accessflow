CREATE TABLE api_keys (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    key_prefix      VARCHAR(16)  NOT NULL,
    key_hash        VARCHAR(128) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT api_keys_unique_name_per_user UNIQUE (user_id, name)
);

CREATE INDEX idx_api_keys_user        ON api_keys (user_id);
CREATE INDEX idx_api_keys_org         ON api_keys (organization_id);
CREATE INDEX idx_api_keys_active_hash ON api_keys (key_hash) WHERE revoked_at IS NULL;
