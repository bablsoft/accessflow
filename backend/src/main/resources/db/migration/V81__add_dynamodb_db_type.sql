-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V81__add_dynamodb_db_type.sql.conf sets executeInTransaction=false so Flyway runs
-- this statement autocommit. The new value powers Amazon DynamoDB (NoSQL, key-value) datasources,
-- whose engine is the native AWS SDK for Java v2 driver (PartiQL) delivered as an on-demand engine
-- plugin rather than a JDBC driver (issue #422).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'DYNAMODB';
