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
    MONGODB
}
