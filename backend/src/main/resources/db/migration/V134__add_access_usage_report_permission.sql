-- #625: least-privilege intelligence. Seeds the new ACCESS_USAGE_REPORT_VIEW permission for the
-- AUDITOR system role, mirroring core.api.SystemRolePermissions. ADMIN holds every catalog
-- value via EnumSet.allOf, so its row is seeded here too. Note the mirror is by convention,
-- not enforcement: system roles resolve from the code map at runtime
-- (DefaultRolePermissionResolver), and no test compares these rows to the enum.
-- role_permissions.permission is VARCHAR — the catalog is code-defined, not a PG enum — so the new
-- value itself needs no DDL.

INSERT INTO role_permissions (role_id, permission) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'ACCESS_USAGE_REPORT_VIEW'),
    ('c0000000-0000-0000-0000-000000000005', 'ACCESS_USAGE_REPORT_VIEW')
ON CONFLICT (role_id, permission) DO NOTHING;

UPDATE roles
SET description = 'Read-only compliance role: compliance reports, attestation evidence, '
                  || 'over-provisioned access, break-glass events, and anomalies.'
WHERE id = 'c0000000-0000-0000-0000-000000000005';
