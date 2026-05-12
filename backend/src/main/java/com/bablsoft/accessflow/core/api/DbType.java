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
    CUSTOM
}
