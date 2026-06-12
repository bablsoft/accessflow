-- AF-421: per-datasource Cassandra/ScyllaDB load-balancing datacenter name. Supplied to the
-- DataStax driver via withLocalDatacenter(...). Nullable here; the datasource service enforces it
-- as required only when db_type is CASSANDRA or SCYLLADB.
ALTER TABLE datasources ADD COLUMN local_datacenter VARCHAR(255);
