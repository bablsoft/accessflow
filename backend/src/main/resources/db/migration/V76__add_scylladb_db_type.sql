-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V76__add_scylladb_db_type.sql.conf sets executeInTransaction=false so Flyway
-- runs this statement autocommit. ScyllaDB is CQL-compatible and served by the very same
-- Cassandra engine plugin JAR (which registers a second QueryEngine provider with
-- engineId="scylladb"); it needs its own db_type only because the connector catalog allows one
-- connector per non-CUSTOM dialect (issue #421).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'SCYLLADB';
