-- #626: result-export governance. Seeds the new EXPORT_POLICY_MANAGE permission for the ADMIN
-- system role, mirroring core.api.SystemRolePermissions (ADMIN holds every catalog value via
-- EnumSet.allOf; the row is seeded here by the V134 convention). Note the mirror is by
-- convention, not enforcement: system roles resolve from the code map at runtime
-- (DefaultRolePermissionResolver), and no test compares these rows to the enum.
-- role_permissions.permission is VARCHAR — the catalog is code-defined, not a PG enum — so the
-- new value itself needs no DDL.

INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'EXPORT_POLICY_MANAGE')
ON CONFLICT (role_id, permission) DO NOTHING;
