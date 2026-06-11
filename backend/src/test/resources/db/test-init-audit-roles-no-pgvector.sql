-- Testcontainer init script for the pgvector-graceful-degradation integration test
-- (PgVectorGracefulDegradationIntegrationTest). Identical to test-init-audit-roles.sql but
-- WITHOUT `CREATE EXTENSION vector` — it runs against a plain (non-pgvector) Postgres image so we
-- can prove the application still starts when the `vector` extension is unavailable: V69 is skipped
-- and knowledge_document is created by V73 instead.

CREATE ROLE accessflow_audit LOGIN PASSWORD 'accessflow_audit';
GRANT CONNECT ON DATABASE test TO accessflow_audit;
GRANT USAGE ON SCHEMA public TO accessflow_audit;

CREATE ROLE accessflow_app LOGIN PASSWORD 'accessflow_app';
GRANT CONNECT ON DATABASE test TO accessflow_app;
GRANT USAGE ON SCHEMA public TO accessflow_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accessflow_app;

CREATE ROLE accessflow LOGIN PASSWORD 'accessflow';
GRANT CONNECT ON DATABASE test TO accessflow;
GRANT USAGE ON SCHEMA public TO accessflow;
