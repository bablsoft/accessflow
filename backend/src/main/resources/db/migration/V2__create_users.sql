CREATE TYPE auth_provider_type AS ENUM ('LOCAL', 'SAML');
CREATE TYPE user_role_type    AS ENUM ('ADMIN', 'REVIEWER', 'ANALYST', 'READONLY');

CREATE TABLE users (
    id              UUID               PRIMARY KEY,
    organization_id UUID               NOT NULL REFERENCES organizations(id),
    email           VARCHAR(255)       NOT NULL UNIQUE,
    display_name    VARCHAR(255),
    password_hash   VARCHAR(255),                  -- NULL for SSO-only users
    auth_provider   auth_provider_type NOT NULL DEFAULT 'LOCAL',
    saml_subject    VARCHAR(255),                  -- SAML NameID, NULL for LOCAL users
    role            user_role_type     NOT NULL,
    is_active       BOOLEAN            NOT NULL DEFAULT true,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP
);
