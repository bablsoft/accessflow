CREATE TYPE edition_type AS ENUM ('COMMUNITY', 'ENTERPRISE');

CREATE TABLE organizations (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255)  NOT NULL,
    slug            VARCHAR(100)  NOT NULL UNIQUE,
    edition         edition_type  NOT NULL DEFAULT 'COMMUNITY',
    saml_config_id  UUID,                          -- FK added in V12 after saml_configurations exists
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
