-- #621: per-organization SCIM 2.0 provisioning configuration (singleton row per org).
-- attr_email / attr_display_name name the SCIM attribute the corresponding user field is read
-- from (attribute mapping); default_role is the system role assigned to SCIM-provisioned users,
-- mirroring saml_config.default_role / oauth2_config.default_role.
CREATE TABLE scim_config (
    id                UUID           PRIMARY KEY,
    organization_id   UUID           NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    enabled           BOOLEAN        NOT NULL DEFAULT FALSE,
    attr_email        VARCHAR(255)   NOT NULL DEFAULT 'userName',
    attr_display_name VARCHAR(255)   NOT NULL DEFAULT 'displayName',
    default_role      user_role_type NOT NULL DEFAULT 'ANALYST',
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
