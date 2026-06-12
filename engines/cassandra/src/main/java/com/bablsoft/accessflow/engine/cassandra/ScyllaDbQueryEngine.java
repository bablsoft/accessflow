package com.bablsoft.accessflow.engine.cassandra;

/**
 * The {@link com.bablsoft.accessflow.core.api.QueryEngine} provider for ScyllaDB. ScyllaDB speaks
 * the very same CQL binary protocol as Apache Cassandra, so this is a thin subclass of
 * {@link CassandraQueryEngine} that changes only {@link #engineId()} to {@code "scylladb"} — the
 * value the host matches against the {@code scylladb} connector id. Registered alongside
 * {@link CassandraQueryEngine} in {@code META-INF/services/...QueryEngine}, so the single shaded
 * JAR backs both connectors (the connector catalog allows one connector per non-CUSTOM dialect,
 * hence the separate {@code DbType.SCYLLADB} and connector, but they share this plugin).
 */
public final class ScyllaDbQueryEngine extends CassandraQueryEngine {

    static final String ENGINE_ID = "scylladb";

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public ScyllaDbQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }
}
