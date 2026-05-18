-- Testcontainer init script (see TestcontainersConfig). Provisions the two non-superuser
-- Postgres roles that V38__audit_log_role_separation.sql operates on:
--   * accessflow_audit — owns audit_log after V38 runs; used by auditDataSource for INSERTs.
--   * accessflow_app   — mirrors the production "general DB_USER" role. Used only by the
--                        AuditRoleSeparationIntegrationTest to assert that REVOKE bites.
--
-- The default POSTGRES_USER (test) stays a superuser and is what Spring's primary DataSource
-- authenticates as in integration tests — V38's REVOKE on accessflow_app does not affect it,
-- so existing audit tests that clean up with `DELETE FROM audit_log` via the primary
-- JdbcTemplate keep working.

CREATE ROLE accessflow_audit LOGIN PASSWORD 'accessflow_audit';
GRANT CONNECT ON DATABASE test TO accessflow_audit;
GRANT USAGE ON SCHEMA public TO accessflow_audit;

CREATE ROLE accessflow_app LOGIN PASSWORD 'accessflow_app';
GRANT CONNECT ON DATABASE test TO accessflow_app;
GRANT USAGE ON SCHEMA public TO accessflow_app;
-- Tables created by Flyway should be readable/writable by accessflow_app by default —
-- this matches the privileges of the production general DB_USER on non-audit tables.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accessflow_app;

-- The Flyway placeholder ${app_role} defaults to "accessflow" in application.yml. Most
-- integration tests do not override it (they only need the migration to apply cleanly),
-- so create the role here too — V38's REVOKE/GRANT statements then have a real target.
-- AuditRoleSeparationIntegrationTest overrides the placeholder to "accessflow_app" so
-- it can connect as a known non-superuser and verify the migration actually bites.
CREATE ROLE accessflow LOGIN PASSWORD 'accessflow';
GRANT CONNECT ON DATABASE test TO accessflow;
GRANT USAGE ON SCHEMA public TO accessflow;
