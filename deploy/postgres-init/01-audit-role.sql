-- Provisions the dedicated `accessflow_audit` Postgres role used by AccessFlow's audit
-- module to INSERT into audit_log. Consumed by the postgres image's
-- /docker-entrypoint-initdb.d/ mechanism — runs once, on the first init of the data
-- volume, as POSTGRES_USER (which the image creates as a superuser).
--
-- The matching V38__audit_log_role_separation.sql Flyway migration transfers ownership
-- of audit_log to this role and revokes UPDATE/DELETE/TRUNCATE from the general DB_USER,
-- so the role MUST exist before the backend boots and runs migrations.
--
-- The role name + password here match the defaults baked into the backend's
-- application.yml (accessflow.audit.datasource.{username,password}) and the
-- docker-compose backend env vars AUDIT_DB_USER / AUDIT_DB_PASSWORD. Override all three
-- in lock-step for production deployments. See docs/09-deployment.md → "audit_log role
-- separation".

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accessflow_audit') THEN
        CREATE ROLE accessflow_audit LOGIN PASSWORD 'accessflow_audit';
    END IF;
END $$;

GRANT CONNECT ON DATABASE accessflow TO accessflow_audit;
GRANT USAGE ON SCHEMA public TO accessflow_audit;

-- Grant the general application role membership in the audit role so V38 can run
-- `ALTER TABLE audit_log OWNER TO accessflow_audit` without superuser privileges.
GRANT accessflow_audit TO accessflow;
