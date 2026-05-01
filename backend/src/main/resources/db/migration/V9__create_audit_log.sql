CREATE TABLE audit_log (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    actor_id        UUID         REFERENCES users(id),  -- NULL for system-generated events
    action          VARCHAR(100) NOT NULL,
    resource_type   VARCHAR(100) NOT NULL,
    resource_id     UUID,
    metadata        JSONB        NOT NULL DEFAULT '{}',
    ip_address      INET,
    user_agent      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
