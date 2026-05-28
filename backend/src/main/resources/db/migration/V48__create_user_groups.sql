-- AF-353: user groups for organising reviewers (and, later, other assignments).
-- A user can belong to many groups; groups are org-scoped. Membership rows
-- carry a `source` (MANUAL or IDP) so the SAML/OAuth2 sync flow can replace
-- only IdP-driven rows without disturbing manually-managed memberships.

CREATE TYPE user_group_membership_source AS ENUM ('MANUAL', 'IDP');

CREATE TABLE user_groups (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_user_groups_org_name_ci
    ON user_groups (organization_id, LOWER(name));

CREATE TABLE user_group_memberships (
    user_id    UUID                          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    group_id   UUID                          NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    source     user_group_membership_source  NOT NULL DEFAULT 'MANUAL',
    joined_at  TIMESTAMPTZ                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, group_id)
);

CREATE INDEX idx_user_group_memberships_group ON user_group_memberships (group_id);
CREATE INDEX idx_user_group_memberships_user_source ON user_group_memberships (user_id, source);
