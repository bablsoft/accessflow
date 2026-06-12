package com.bablsoft.accessflow.engine.elasticsearch;

/**
 * Selects which low-level REST client backs a {@link SearchTransport}. The single shaded plugin JAR
 * registers two {@code QueryEngine} providers — {@code elasticsearch} and {@code opensearch} — that
 * differ only by this flavor: {@link #ELASTICSEARCH} uses the Elastic
 * {@code org.elasticsearch.client.RestClient}, {@link #OPENSEARCH} the OpenSearch
 * {@code org.opensearch.client.RestClient}. Everything else (parser, row-security, masking,
 * introspection) is shared, the Elasticsearch/OpenSearch analogue of one Cassandra JAR serving
 * ScyllaDB.
 */
enum TransportFlavor {
    ELASTICSEARCH,
    OPENSEARCH
}
