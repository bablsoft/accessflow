-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V82__add_neo4j_db_type.sql.conf sets executeInTransaction=false so Flyway runs this
-- statement autocommit. The new value powers Neo4j (NoSQL, graph) datasources, whose engine is the
-- native Bolt-protocol Neo4j Java driver delivered as an on-demand engine plugin rather than a JDBC
-- driver (issue #423).
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'NEO4J';
