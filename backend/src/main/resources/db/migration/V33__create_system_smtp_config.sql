CREATE TABLE system_smtp_config (
    id                 UUID         PRIMARY KEY,
    organization_id    UUID         NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    host               VARCHAR(255) NOT NULL,
    port               INTEGER      NOT NULL,
    username           VARCHAR(255),
    password_encrypted TEXT,
    tls                BOOLEAN      NOT NULL DEFAULT TRUE,
    from_address       VARCHAR(255) NOT NULL,
    from_name          VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
