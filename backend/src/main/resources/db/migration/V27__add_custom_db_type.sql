-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V27__add_custom_db_type.sql.conf sets executeInTransaction=false so Flyway
-- runs this statement autocommit. The new value powers admin-uploaded JDBC drivers and
-- fully dynamic datasources whose dialect is not one of the bundled five.
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'CUSTOM';
