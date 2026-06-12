-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V74__add_redis_db_type.sql.conf sets executeInTransaction=false so Flyway
-- runs this statement autocommit. The new value powers Redis (NoSQL, key-value) datasources,
-- whose engine is the native Jedis driver delivered as an on-demand engine plugin rather than
-- a JDBC driver (issue #419).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'REDIS';
