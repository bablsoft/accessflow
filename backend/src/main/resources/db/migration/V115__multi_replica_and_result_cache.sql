-- AF-457: multi-replica read load balancing — replaces the single read_replica_*
-- columns (V46) with a datasource_read_replicas child table, and adds the opt-in
-- SELECT result-cache flags to datasources.

CREATE TABLE datasource_read_replicas (
    id                  UUID        PRIMARY KEY,
    datasource_id       UUID        NOT NULL REFERENCES datasources(id) ON DELETE CASCADE,
    jdbc_url            TEXT        NOT NULL,
    username            VARCHAR(255),
    password_encrypted  TEXT,
    position            INT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_datasource_read_replicas_datasource
    ON datasource_read_replicas (datasource_id);

INSERT INTO datasource_read_replicas (id, datasource_id, jdbc_url, username, password_encrypted, position)
SELECT gen_random_uuid(), id, read_replica_jdbc_url, read_replica_username, read_replica_password_encrypted, 0
FROM datasources
WHERE read_replica_jdbc_url IS NOT NULL AND read_replica_jdbc_url <> '';

ALTER TABLE datasources
    DROP COLUMN read_replica_jdbc_url,
    DROP COLUMN read_replica_username,
    DROP COLUMN read_replica_password_encrypted,
    ADD COLUMN result_cache_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN result_cache_ttl_seconds INT;
