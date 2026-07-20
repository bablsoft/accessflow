-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V122__add_postman_collection_schema_type.sql.conf sets executeInTransaction=false so
-- Flyway runs this statement autocommit. The new value lets a Postman Collection v2.x export act as
-- a connector schema source, parsed by PostmanCollectionParser (issue #612).
ALTER TYPE api_schema_type ADD VALUE IF NOT EXISTS 'POSTMAN_COLLECTION';
