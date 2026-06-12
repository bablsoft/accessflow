package com.bablsoft.accessflow.engine.elasticsearch;

/**
 * The {@link com.bablsoft.accessflow.core.api.QueryEngine} provider for OpenSearch. OpenSearch speaks
 * the same REST API and Query DSL as Elasticsearch for the operations AccessFlow governs, so this is
 * a thin subclass of {@link ElasticsearchQueryEngine} that changes only {@link #engineId()} to
 * {@code "opensearch"} (matched against the {@code opensearch} connector id) and {@link #flavor()} to
 * {@link TransportFlavor#OPENSEARCH} (the OpenSearch low-level REST client). Registered alongside
 * {@link ElasticsearchQueryEngine} in {@code META-INF/services/...QueryEngine}, so the single shaded
 * JAR backs both connectors — the search-engine analogue of one Cassandra JAR serving ScyllaDB.
 */
public final class OpenSearchQueryEngine extends ElasticsearchQueryEngine {

    static final String ENGINE_ID = "opensearch";

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public OpenSearchQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    protected TransportFlavor flavor() {
        return TransportFlavor.OPENSEARCH;
    }
}
