-- #621: SCIM resource identity columns.
-- scim_external_id is the IdP-side identifier (SCIM externalId), unique per org when set.
-- users.updated_at feeds SCIM meta.lastModified (backfilled from created_at).
ALTER TABLE users ADD COLUMN scim_external_id VARCHAR(255);
CREATE UNIQUE INDEX uq_users_org_scim_external_id
    ON users (organization_id, scim_external_id) WHERE scim_external_id IS NOT NULL;

ALTER TABLE users ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE users SET updated_at = created_at;

ALTER TABLE user_groups ADD COLUMN scim_external_id VARCHAR(255);
CREATE UNIQUE INDEX uq_user_groups_org_scim_external_id
    ON user_groups (organization_id, scim_external_id) WHERE scim_external_id IS NOT NULL;
