CREATE TYPE oauth2_provider_type AS ENUM ('GOOGLE', 'GITHUB', 'MICROSOFT', 'GITLAB');

CREATE TABLE oauth2_config (
    id                       UUID                 PRIMARY KEY,
    organization_id          UUID                 NOT NULL REFERENCES organizations(id),
    provider                 oauth2_provider_type NOT NULL,
    client_id                VARCHAR(512)         NOT NULL,
    client_secret_encrypted  TEXT                 NOT NULL,
    scopes_override          VARCHAR(1024),
    tenant_id                VARCHAR(255),
    default_role             user_role_type       NOT NULL DEFAULT 'ANALYST',
    active                   BOOLEAN              NOT NULL DEFAULT FALSE,
    version                  BIGINT               NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, provider)
);

CREATE INDEX oauth2_config_org_active_idx ON oauth2_config(organization_id) WHERE active;
