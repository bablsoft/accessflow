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
    SCYLLADB,
    /**
     * Elasticsearch — a NoSQL search engine. Not JDBC-backed: the proxy connects via the native
     * low-level REST client, validates an AccessFlow JSON query envelope rather than JSqlParser, and
     * executes search / count / index / bulk / update_by_query / delete_by_query / index-management
     * requests instead of SQL. Row security is injected as {@code bool.filter} clauses (keyword
     * fields only); server-side scripting and cluster APIs are rejected. Resolved on demand through
     * the connector catalog as an engine plugin. See {@code docs/05-backend.md} →
     * "Elasticsearch engine" and {@code docs/14-connectors.md}.
     */
    ELASTICSEARCH,
    /**
     * OpenSearch — a search engine wire-compatible with Elasticsearch for the governed operations.
     * Served by the very same Elasticsearch engine plugin JAR (which registers a second
     * {@code QueryEngine} provider with {@code engineId="opensearch"}, differing only in the
     * low-level REST client used); it needs its own {@code DbType} only because the connector catalog
     * allows one connector per non-{@code CUSTOM} dialect. Behaviour is identical to
     * {@link #ELASTICSEARCH}. See {@code docs/05-backend.md} → "Elasticsearch engine" and
     * {@code docs/14-connectors.md}.
     */
    OPENSEARCH,
    /**
     * Amazon DynamoDB — a NoSQL key-value store queried with PartiQL. Not JDBC-backed: the proxy
     * connects via the native AWS SDK for Java v2 ({@code DynamoDbClient}) with the url-connection
     * HTTP client, validates PartiQL statements (and JSON table-management commands) with a PartiQL
     * classifier rather than JSqlParser, and resolves the engine plugin on demand through the
     * connector catalog. Its "connection" is cloud credentials, not host/port:
     * {@code database_name} = AWS region, {@code username} = access key id,
     * {@code password_encrypted} = secret access key, and {@code jdbc_url_override} = an optional
     * custom endpoint (DynamoDB Local / VPC). Row security splices predicates into the PartiQL WHERE
     * clause (parameter-bound; fail-closed on shapes that cannot be filtered). See
     * {@code docs/05-backend.md} → "DynamoDB engine" and {@code docs/14-connectors.md}.
     */
    DYNAMODB,
    /**
     * Neo4j — a NoSQL graph engine queried with Cypher. Not JDBC-backed: the proxy connects via the
     * native Neo4j Java driver ({@code org.neo4j.driver.Driver}) over the Bolt protocol, validates
     * Cypher statements with a Cypher classifier rather than JSqlParser, and resolves the engine
     * plugin on demand through the connector catalog. The connection is a {@code bolt://} /
     * {@code neo4j://} URI built from host/port + SSL mode (or supplied verbatim via
     * {@code jdbc_url_override} for Aura / clustered routing), with {@code database_name} selecting
     * the Neo4j database. Row security ANDs property predicates onto each {@code MATCH} whose label
     * matches the policy (parameter-bound; fail-closed on shapes that cannot be rewritten);
     * procedure calls outside a read-only allow-list and {@code LOAD CSV} are rejected. See
     * {@code docs/05-backend.md} → "Neo4j engine" and {@code docs/14-connectors.md}.
     */
    NEO4J,
    /**
     * Snowflake — a cloud data warehouse queried with Snowflake SQL. Engine-managed (not pooled
     * JDBC): the engine plugin bundles the Snowflake JDBC driver, opens a short-lived connection
     * per request (warehouse sessions are billed while resumed), validates statements with a
     * Snowflake SQL classifier rather than JSqlParser, and resolves the plugin on demand through
     * the connector catalog. Connection mapping: {@code host} = account host
     * ({@code <account>.snowflakecomputing.com}), {@code database_name} = database,
     * {@code username} = user, {@code password_encrypted} = password <em>or</em> a PKCS#8
     * private-key PEM (key-pair JWT auth, detected by the {@code -----BEGIN} prefix), and
     * {@code jdbc_url_override} = an optional full {@code jdbc:snowflake://} URL carrying
     * warehouse / role / schema parameters. Row security splices parameter-bound predicates into
     * the WHERE clause (fail-closed on CTE / subquery / JOIN / set-op shapes). See
     * {@code docs/05-backend.md} → "Snowflake engine" and {@code docs/14-connectors.md}.
     */
    SNOWFLAKE,
    /**
     * Google BigQuery — a cloud data warehouse queried with GoogleSQL. Engine-managed (not
     * JDBC-backed): the engine plugin connects via the native {@code google-cloud-bigquery}
     * HTTP/JSON client, validates statements with a GoogleSQL classifier rather than JSqlParser,
     * and resolves the plugin on demand through the connector catalog. Its "connection" is cloud
     * credentials, not host/port: {@code database_name} = GCP project id (optionally
     * {@code project.dataset} to pin a default dataset), {@code password_encrypted} = the
     * service-account key JSON, and {@code jdbc_url_override} = an optional custom endpoint
     * (emulator). Row security splices positional-parameter predicates into the WHERE clause
     * (fail-closed on unrewritable shapes); scripting ({@code BEGIN} / {@code DECLARE} /
     * {@code CALL} / {@code EXECUTE IMMEDIATE}) is rejected. See {@code docs/05-backend.md} →
     * "BigQuery engine" and {@code docs/14-connectors.md}.
     */
    BIGQUERY,
    /**
     * Databricks SQL — a lakehouse SQL warehouse queried with Databricks SQL. Engine-managed (not
     * JDBC-backed): the engine plugin talks to the SQL Statement Execution REST API over the JDK
     * HTTP client (submit / poll / cancel), validates statements with a Databricks SQL classifier
     * rather than JSqlParser, and resolves the plugin on demand through the connector catalog.
     * Connection mapping: {@code host} = workspace host, {@code password_encrypted} = personal
     * access token, {@code jdbc_url_override} = the warehouse HTTP path
     * ({@code /sql/1.0/warehouses/<id>}, required), and {@code database_name} = an optional Unity
     * Catalog catalog. Row security splices named-parameter predicates into the WHERE clause
     * (fail-closed on unrewritable shapes); maintenance ops ({@code OPTIMIZE} / {@code VACUUM})
     * and {@code COPY INTO} are rejected. See {@code docs/05-backend.md} → "Databricks engine"
     * and {@code docs/14-connectors.md}.
     */
    DATABRICKS
}
