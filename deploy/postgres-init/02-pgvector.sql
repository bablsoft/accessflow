-- Provisions the pgvector `vector` extension required by AccessFlow's in-app RAG knowledge base
-- (AF-336, rag_store_type = PGVECTOR). Consumed by the postgres image's
-- /docker-entrypoint-initdb.d/ mechanism — runs once, on the first init of the data volume, as
-- POSTGRES_USER (which the image creates as a superuser).
--
-- `vector` is NOT a trusted extension, so CREATE EXTENSION requires a superuser. The general
-- application DB role (DB_USER) is not a superuser, which is why this runs at init time rather than
-- from the V69 Flyway migration. V69 creates only the `vector_store` table and assumes the type
-- already exists, so the extension MUST be present before the backend boots and runs migrations.
--
-- Requires a pgvector-enabled Postgres image (e.g. pgvector/pgvector:pgNN, or the Bitnami
-- postgresql image which bundles the extension). For external / managed Postgres the operator
-- installs the extension manually. See docs/09-deployment.md → "pgvector for RAG".

CREATE EXTENSION IF NOT EXISTS vector;
