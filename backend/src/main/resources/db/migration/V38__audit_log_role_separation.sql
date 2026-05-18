-- Role separation for audit_log (issue #67).
--
-- After this migration:
--   * ${audit_role} owns audit_log and is the only role expected to INSERT (used by the
--     auditDataSource bean in the audit module).
--   * ${app_role} keeps SELECT for the admin read endpoint, but UPDATE/DELETE/TRUNCATE
--     are revoked. Without ownership, ${app_role} no longer carries implicit privileges,
--     so the REVOKE actually bites at the database layer.
--
-- The Flyway placeholders default to the same values used by docker-compose and the Helm
-- chart (see CLAUDE.md → Configuration). The audit role must exist before this migration
-- runs — Postgres init scripts under deploy/postgres-init/ provision it for the demo and
-- dev stacks; the Helm chart wires it through postgresql.primary.initdb.scripts.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${audit_role}') THEN
        RAISE EXCEPTION
            'Audit writer role "%" does not exist. Provision it before applying this migration — see docs/09-deployment.md → "audit_log role separation".',
            '${audit_role}';
    END IF;
END $$;

ALTER TABLE audit_log OWNER TO ${audit_role};

REVOKE ALL ON audit_log FROM PUBLIC;
REVOKE ALL ON audit_log FROM ${app_role};
GRANT SELECT ON audit_log TO ${app_role};
