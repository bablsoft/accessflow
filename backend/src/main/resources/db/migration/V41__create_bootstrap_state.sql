-- AF-196: per-resource fingerprint cache used by the bootstrap reconciler to detect "no change"
-- and skip both the underlying service upsert and the corresponding audit_log row. resource_id is
-- the entity UUID for normal resources, the organization UUID for singleton org-level configs
-- (SAML, SystemSmtp), and a deterministic UUID derived from the provider name for OAuth2 rows.
--
-- No FKs: consistent with V14's removal of audit_log FKs so bootstrap_state survives org / resource
-- deletion and stays modulith-isolated from core/security.
CREATE TABLE bootstrap_state (
    id                UUID         PRIMARY KEY,
    organization_id   UUID         NOT NULL,
    resource_type     VARCHAR(100) NOT NULL,
    resource_id       UUID         NOT NULL,
    spec_fingerprint  VARCHAR(64)  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_bootstrap_state_key UNIQUE (organization_id, resource_type, resource_id)
);
