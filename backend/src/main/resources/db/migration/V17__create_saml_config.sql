CREATE TABLE saml_config (
    id                  UUID         PRIMARY KEY,
    organization_id     UUID         NOT NULL UNIQUE REFERENCES organizations(id),
    idp_metadata_url    VARCHAR(1024),
    idp_entity_id       VARCHAR(1024),
    sp_entity_id        VARCHAR(1024),
    acs_url             VARCHAR(1024),
    slo_url             VARCHAR(1024),
    signing_cert_pem    TEXT,
    attr_email          VARCHAR(255) NOT NULL DEFAULT 'email',
    attr_display_name   VARCHAR(255) NOT NULL DEFAULT 'displayName',
    attr_role           VARCHAR(255),
    default_role        user_role_type NOT NULL DEFAULT 'ANALYST',
    active              BOOLEAN      NOT NULL DEFAULT FALSE,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
