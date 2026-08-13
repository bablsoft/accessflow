-- #621: long-lived SCIM bearer tokens (one or more named tokens per org, so an operator can
-- rotate without downtime). Only a SHA-256 hex hash is stored; the raw token is shown exactly
-- once at creation. Shape mirrors api_keys (V32).
CREATE TABLE scim_tokens (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    token_prefix    VARCHAR(16)  NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    created_by      UUID         REFERENCES users(id) ON DELETE SET NULL,
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT scim_tokens_unique_name_per_org UNIQUE (organization_id, name)
);

CREATE INDEX idx_scim_tokens_org ON scim_tokens (organization_id);
-- Authentication does a hash lookup on every SCIM request; revoked tokens drop out of the index.
CREATE INDEX idx_scim_tokens_active_hash ON scim_tokens (token_hash) WHERE revoked_at IS NULL;
