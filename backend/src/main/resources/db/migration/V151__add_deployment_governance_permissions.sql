-- #684 (epic #682): deployment approval governance. Seeds the new DEPLOYMENT_PIPELINE_MANAGE
-- (ADMIN) and DEPLOYMENT_REVIEW (ADMIN + REVIEWER) permissions for the system roles, mirroring
-- core.api.SystemRolePermissions (ADMIN holds every catalog value via EnumSet.allOf; REVIEWER
-- gains the per-domain review permission — SystemRoleSeedParityIntegrationTest keeps the rows and
-- the code map in sync). role_permissions.permission is VARCHAR — the catalog is code-defined,
-- not a PG enum — so the new values themselves need no DDL.

INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'DEPLOYMENT_PIPELINE_MANAGE'),
    ('c0000000-0000-0000-0000-000000000001', 'DEPLOYMENT_REVIEW'),
    ('c0000000-0000-0000-0000-000000000002', 'DEPLOYMENT_REVIEW')
ON CONFLICT (role_id, permission) DO NOTHING;
