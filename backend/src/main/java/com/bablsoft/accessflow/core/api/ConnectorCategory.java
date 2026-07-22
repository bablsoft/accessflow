package com.bablsoft.accessflow.core.api;

/**
 * Broad family of a database connector, used to group the connector catalog into SQL, cloud
 * data-warehouse, and NoSQL sections in the marketplace UI and to relax the JDBC-specific
 * manifest requirements for native engines.
 *
 * <p>{@link #RELATIONAL} is the JDBC/SQL umbrella; every other value is engine-managed: a
 * connector that connects through a native driver shipped as an engine plugin (see
 * {@code docs/15-engine-sdk.md}) and therefore carries no {@code jdbcUrlTemplate} or
 * {@code driverClassName}. {@link #WAREHOUSE} engines speak SQL dialects but are still
 * engine-managed because their connection and auth models (key-pair JWT, service-account JSON,
 * personal access tokens) do not fit the pooled JDBC host/port/username/password lane; the
 * remaining values form the NoSQL umbrella.
 *
 * <ul>
 *   <li>{@link #RELATIONAL} — a JDBC/SQL engine (PostgreSQL, MySQL, MariaDB, Oracle, MSSQL, and
 *       JDBC-compatible CUSTOM connectors). Carries a {@code jdbcUrlTemplate} and
 *       {@code driverClassName}.</li>
 *   <li>{@link #WAREHOUSE} — a cloud data warehouse (Snowflake, BigQuery, Databricks SQL).
 *       SQL-dialect but engine-managed (native auth models, no pooled JDBC).</li>
 *   <li>{@link #DOCUMENT} — a document store (MongoDB).</li>
 *   <li>{@link #KEY_VALUE} — a key-value store (Redis and compatibles).</li>
 *   <li>{@link #WIDE_COLUMN} — a wide-column store (Cassandra and compatibles).</li>
 *   <li>{@link #SEARCH} — a search engine (Elasticsearch / OpenSearch).</li>
 *   <li>{@link #GRAPH} — a graph database (Neo4j and compatibles).</li>
 * </ul>
 */
public enum ConnectorCategory {
    RELATIONAL,
    WAREHOUSE,
    DOCUMENT,
    KEY_VALUE,
    WIDE_COLUMN,
    SEARCH,
    GRAPH
}
