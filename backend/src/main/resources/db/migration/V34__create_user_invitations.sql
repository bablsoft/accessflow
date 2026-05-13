CREATE TYPE user_invitation_status AS ENUM ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED');

CREATE TABLE user_invitations (
    id                  UUID                   PRIMARY KEY,
    organization_id     UUID                   NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email               VARCHAR(255)           NOT NULL,
    role                user_role_type         NOT NULL,
    display_name        VARCHAR(255),
    token_hash          VARCHAR(64)            NOT NULL UNIQUE,
    status              user_invitation_status NOT NULL DEFAULT 'PENDING',
    expires_at          TIMESTAMPTZ            NOT NULL,
    accepted_at         TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    invited_by_user_id  UUID                   NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ            NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_invitations_org_status_created
    ON user_invitations(organization_id, status, created_at DESC);

CREATE UNIQUE INDEX uq_user_invitations_pending_email
    ON user_invitations(organization_id, LOWER(email))
    WHERE status = 'PENDING';
