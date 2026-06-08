-- Connector-backed datasources (issue #334). A datasource may reference a catalog connector by
-- its manifest id (e.g. 'clickhouse'). Used for CUSTOM-dialect engines whose driver is installed
-- from the connector marketplace: the proxy loads the connector's cached JDBC driver into a
-- per-connector classloader and builds the JDBC URL from the connector's template. Null for the
-- five first-class dialects and for admin-uploaded-driver datasources.
ALTER TABLE datasources
    ADD COLUMN connector_id VARCHAR(64);
