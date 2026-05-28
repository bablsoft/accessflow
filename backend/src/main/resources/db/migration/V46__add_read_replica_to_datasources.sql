ALTER TABLE datasources
    ADD COLUMN read_replica_jdbc_url TEXT,
    ADD COLUMN read_replica_username VARCHAR(255),
    ADD COLUMN read_replica_password_encrypted TEXT;
