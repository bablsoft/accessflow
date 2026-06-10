-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V71__add_mongodb_db_type.sql.conf sets executeInTransaction=false so Flyway
-- runs this statement autocommit. The new value powers MongoDB (NoSQL) datasources, whose
-- engine is the native mongodb-driver-sync rather than a JDBC driver (issue #411).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'MONGODB';
