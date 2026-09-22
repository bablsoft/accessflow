-- #878 (epic #870): schema change governance. Seeds the new SCHEMA_CHANGE_MANAGE permission for
-- the ADMIN system role, mirroring core.api.SystemRolePermissions (ADMIN holds every catalog value
-- via EnumSet.allOf; the row is seeded here by the V134 convention). role_permissions.permission
-- is VARCHAR — the catalog is code-defined, not a PG enum — so the new value itself needs no DDL.

INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'SCHEMA_CHANGE_MANAGE')
ON CONFLICT (role_id, permission) DO NOTHING;
