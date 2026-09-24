-- #934: seeds the new ROW_LIMIT_POLICY_MANAGE permission for the ADMIN system role, mirroring
-- core.api.SystemRolePermissions (ADMIN holds every catalog value via EnumSet.allOf).
-- role_permissions.permission is VARCHAR, so the new value itself needs no DDL.

INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'ROW_LIMIT_POLICY_MANAGE')
ON CONFLICT (role_id, permission) DO NOTHING;
