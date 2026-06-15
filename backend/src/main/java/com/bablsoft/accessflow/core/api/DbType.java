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
    NEO4J
}
