-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V72__add_couchbase_db_type.sql.conf sets executeInTransaction=false so Flyway
-- runs this statement autocommit. The new value powers Couchbase (NoSQL, SQL++) datasources,
-- whose engine is the native Couchbase Java SDK delivered as an on-demand engine plugin rather
-- than a JDBC driver (issue #412).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'COUCHBASE';
