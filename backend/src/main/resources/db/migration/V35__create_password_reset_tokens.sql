CREATE TYPE password_reset_status AS ENUM ('PENDING', 'USED', 'REVOKED', 'EXPIRED');

CREATE TABLE password_reset_tokens (
    id              UUID                  PRIMARY KEY,
    user_id         UUID                  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id UUID                  NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)           NOT NULL UNIQUE,
    status          password_reset_status NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMPTZ           NOT NULL,
    used_at         TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ           NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_password_reset_tokens_user_status_created
    ON password_reset_tokens(user_id, status, created_at DESC);

CREATE UNIQUE INDEX uq_password_reset_tokens_pending_user
    ON password_reset_tokens(user_id) WHERE status = 'PENDING';
