-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V75__add_cassandra_db_type.sql.conf sets executeInTransaction=false so Flyway
-- runs this statement autocommit. The new value powers Apache Cassandra (NoSQL, wide-column, CQL)
-- datasources, whose engine is the native DataStax Java driver delivered as an on-demand engine
-- plugin rather than a JDBC driver (issue #421).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'CASSANDRA';
