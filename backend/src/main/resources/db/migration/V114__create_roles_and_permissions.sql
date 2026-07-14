-- AF-522: custom roles composed from the code-defined functional permission catalog.
-- The 5 built-in roles become immutable GLOBAL system rows (organization_id IS NULL) shared by
-- every organization; custom roles are org-scoped. The permission catalog itself is fixed in code
-- (core.api.Permission) — role_permissions stores enum names as text. The seeds below mirror
-- core.api.SystemRolePermissions exactly (a parity test keeps them in sync); at runtime system
-- roles are resolved from the code map, so these rows are the catalog/UI source only.
-- users.role / user_invitations.role stay populated for system-role users (backward compat with
-- bootstrap / Terraform / SSO default_role) and become NULL for custom-role users.

CREATE TABLE roles (
    id              UUID         PRIMARY KEY,
    organization_id UUID         REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    is_system       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_roles_system_global CHECK (is_system = (organization_id IS NULL))
);

CREATE UNIQUE INDEX uq_roles_system_name ON roles (LOWER(name)) WHERE organization_id IS NULL;
CREATE UNIQUE INDEX uq_roles_org_name    ON roles (organization_id, LOWER(name)) WHERE organization_id IS NOT NULL;
CREATE INDEX idx_roles_org ON roles (organization_id);

CREATE TABLE role_permissions (
    role_id    UUID         NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

INSERT INTO roles (id, organization_id, name, description, is_system) VALUES
    ('c0000000-0000-0000-0000-000000000001', NULL, 'ADMIN',    'Full administrative access to every AccessFlow capability.', TRUE),
    ('c0000000-0000-0000-0000-000000000002', NULL, 'REVIEWER', 'Submits SELECT/DML queries and reviews queries, access requests, API requests, erasure requests, and attestation items.', TRUE),
    ('c0000000-0000-0000-0000-000000000003', NULL, 'ANALYST',  'Submits SELECT and DML queries and views own history.', TRUE),
    ('c0000000-0000-0000-0000-000000000004', NULL, 'READONLY', 'Submits SELECT queries only.', TRUE),
    ('c0000000-0000-0000-0000-000000000005', NULL, 'AUDITOR',  'Read-only compliance role: compliance reports, attestation evidence, break-glass events, and anomalies.', TRUE);

-- ADMIN: every permission in the catalog.
INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'QUERY_SUBMIT_SELECT'),
    ('c0000000-0000-0000-0000-000000000001', 'QUERY_SUBMIT_DML'),
    ('c0000000-0000-0000-0000-000000000001', 'QUERY_SUBMIT_DDL'),
    ('c0000000-0000-0000-0000-000000000001', 'QUERY_VIEW_ALL'),
    ('c0000000-0000-0000-0000-000000000001', 'QUERY_REVIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'REVIEW_OVERRIDE'),
    ('c0000000-0000-0000-0000-000000000001', 'QUERY_ADMIN'),
    ('c0000000-0000-0000-0000-000000000001', 'ACCESS_REQUEST_REVIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'ACCESS_GRANT_REVOKE'),
    ('c0000000-0000-0000-0000-000000000001', 'API_CONNECTOR_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'API_REQUEST_REVIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'DATASOURCE_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'DATASOURCE_PERMISSION_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'MASKING_POLICY_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'ROW_SECURITY_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'DATA_CLASSIFICATION_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'REVIEW_PLAN_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'ROUTING_POLICY_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'BREAK_GLASS_VIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'BREAK_GLASS_REVIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'RETENTION_POLICY_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'ERASURE_REVIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'ATTESTATION_CAMPAIGN_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'ATTESTATION_REVIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'ATTESTATION_EVIDENCE_EXPORT'),
    ('c0000000-0000-0000-0000-000000000001', 'COMPLIANCE_REPORT_VIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'AUDIT_LOG_VIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'ANOMALY_VIEW'),
    ('c0000000-0000-0000-0000-000000000001', 'ANOMALY_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'USER_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'GROUP_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'ROLE_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'AI_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'NOTIFICATION_CHANNEL_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'SSO_CONFIGURE'),
    ('c0000000-0000-0000-0000-000000000001', 'SMTP_CONFIGURE'),
    ('c0000000-0000-0000-0000-000000000001', 'LOCALIZATION_CONFIGURE'),
    ('c0000000-0000-0000-0000-000000000001', 'SETUP_PROGRESS_VIEW');

-- REVIEWER: ANALYST capabilities + the per-domain review permissions.
INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000002', 'QUERY_SUBMIT_SELECT'),
    ('c0000000-0000-0000-0000-000000000002', 'QUERY_SUBMIT_DML'),
    ('c0000000-0000-0000-0000-000000000002', 'QUERY_VIEW_ALL'),
    ('c0000000-0000-0000-0000-000000000002', 'QUERY_REVIEW'),
    ('c0000000-0000-0000-0000-000000000002', 'ACCESS_REQUEST_REVIEW'),
    ('c0000000-0000-0000-0000-000000000002', 'API_REQUEST_REVIEW'),
    ('c0000000-0000-0000-0000-000000000002', 'ERASURE_REVIEW'),
    ('c0000000-0000-0000-0000-000000000002', 'ATTESTATION_REVIEW');

-- ANALYST: SELECT + DML.
INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000003', 'QUERY_SUBMIT_SELECT'),
    ('c0000000-0000-0000-0000-000000000003', 'QUERY_SUBMIT_DML');

-- READONLY: SELECT only.
INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000004', 'QUERY_SUBMIT_SELECT');

-- AUDITOR: read-only compliance surfaces.
INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000005', 'COMPLIANCE_REPORT_VIEW'),
    ('c0000000-0000-0000-0000-000000000005', 'ATTESTATION_EVIDENCE_EXPORT'),
    ('c0000000-0000-0000-0000-000000000005', 'BREAK_GLASS_VIEW'),
    ('c0000000-0000-0000-0000-000000000005', 'ANOMALY_VIEW');

ALTER TABLE users ADD COLUMN role_id UUID REFERENCES roles(id);
UPDATE users SET role_id = (SELECT r.id FROM roles r WHERE r.is_system AND r.name = users.role::text);
ALTER TABLE users ALTER COLUMN role DROP NOT NULL;
CREATE INDEX idx_users_role_id ON users (role_id);

ALTER TABLE user_invitations ADD COLUMN role_id UUID REFERENCES roles(id);
UPDATE user_invitations SET role_id = (SELECT r.id FROM roles r WHERE r.is_system AND r.name = user_invitations.role::text);
ALTER TABLE user_invitations ALTER COLUMN role DROP NOT NULL;

-- Approver rules match by role NAME from now on (system or custom), so the column widens from the
-- pg enum to plain text.
ALTER TABLE review_plan_approvers ALTER COLUMN role TYPE VARCHAR(100) USING role::text;
