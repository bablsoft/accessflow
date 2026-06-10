package com.bablsoft.accessflow.core.api;

/**
 * Broad family of a database connector, used to group the connector catalog into SQL and NoSQL
 * sections in the marketplace UI and to relax the JDBC-specific manifest requirements for native
 * engines.
 *
 * <ul>
 *   <li>{@link #RELATIONAL} — a JDBC/SQL engine (PostgreSQL, MySQL, MariaDB, Oracle, MSSQL, and
 *       JDBC-compatible CUSTOM connectors). Carries a {@code jdbcUrlTemplate} and
 *       {@code driverClassName}.</li>
 *   <li>{@link #DOCUMENT} — a NoSQL document engine (MongoDB). Connects through a native driver
 *       rather than JDBC, so it has no JDBC URL template or driver class.</li>
 * </ul>
 */
public enum ConnectorCategory {
    RELATIONAL,
    DOCUMENT
}
