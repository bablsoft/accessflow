-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V78__add_elasticsearch_db_type.sql.conf sets executeInTransaction=false so Flyway
-- runs this statement autocommit. The new value powers Elasticsearch (NoSQL, search) datasources,
-- whose engine is the native low-level REST client delivered as an on-demand engine plugin rather
-- than a JDBC driver (issue #420).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'ELASTICSEARCH';
