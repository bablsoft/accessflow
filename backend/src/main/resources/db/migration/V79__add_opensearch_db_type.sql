-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V79__add_opensearch_db_type.sql.conf sets executeInTransaction=false so Flyway runs
-- this statement autocommit. The new value powers OpenSearch (NoSQL, search) datasources, served by
-- the very same Elasticsearch engine plugin JAR (a second QueryEngine provider, engineId
-- "opensearch") rather than a JDBC driver (issue #420).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'OPENSEARCH';
