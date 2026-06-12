package com.bablsoft.accessflow.core.api;

public enum DbType {
    POSTGRESQL,
    MYSQL,
    MARIADB,
    ORACLE,
    MSSQL,
    /**
     * Free-form dialect for datasources backed by an admin-uploaded JDBC driver. Datasources of
     * this type carry their own JDBC URL ({@code jdbc_url_override}) and reference a
     * {@code custom_jdbc_driver} row; the proxy engine resolves the per-driver classloader
     * rather than the bundled registry entry.
     */
    CUSTOM,
    /**
     * MongoDB — a NoSQL document engine. Unlike the relational dialects above, MongoDB is not
     * JDBC-backed: the proxy connects via the native {@code mongodb-driver-sync} ({@code MongoClient}),
     * validates queries with a MongoDB query parser rather than JSqlParser, and executes find /
     * aggregate / insert / update / delete / DDL commands instead of SQL. See
     * {@code docs/05-backend.md} → "MongoDB engine" and {@code docs/14-connectors.md}.
     */
    MONGODB,
    /**
     * Couchbase — a NoSQL document engine queried with SQL++ (N1QL). Not JDBC-backed: the proxy
     * connects via the native Couchbase Java SDK ({@code Cluster}), validates queries with a
     * SQL++ classifier rather than JSqlParser, and resolves the engine plugin on demand through
     * the connector catalog. See {@code docs/05-backend.md} → "Couchbase engine" and
     * {@code docs/14-connectors.md}.
     */
    COUCHBASE,
    /**
     * Redis — a NoSQL key-value store. Not JDBC-backed: the proxy connects via the native Jedis
     * driver, validates queries with a Redis command parser rather than JSqlParser, and executes
     * redis-cli commands (GET / HGETALL / SCAN / SET / DEL / …) instead of SQL. Resolved on demand
     * through the connector catalog as an engine plugin. See {@code docs/05-backend.md} →
     * "Redis engine" and {@code docs/14-connectors.md}.
     */
    REDIS,
    /**
     * Apache Cassandra — a NoSQL wide-column store queried with CQL. Not JDBC-backed: the proxy
     * connects via the native DataStax Java driver ({@code CqlSession}), validates queries with a
     * CQL classifier rather than JSqlParser, and resolves the engine plugin on demand through the
     * connector catalog. Row security only splices predicates on partition/clustering key columns
     * (fail-closed otherwise — no {@code ALLOW FILTERING}). See {@code docs/05-backend.md} →
     * "Cassandra engine" and {@code docs/14-connectors.md}.
     */
    CASSANDRA,
    /**
     * ScyllaDB — a CQL-compatible wide-column store. Served by the very same Cassandra engine plugin
     * JAR (which registers a second {@code QueryEngine} provider with {@code engineId="scylladb"});
     * it needs its own {@code DbType} only because the connector catalog allows one connector per
     * non-{@code CUSTOM} dialect. Behaviour is identical to {@link #CASSANDRA}. See
     * {@code docs/05-backend.md} → "Cassandra engine" and {@code docs/14-connectors.md}.
     */
    SCYLLADB
}
