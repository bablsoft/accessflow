package com.bablsoft.accessflow.engine.elasticsearch;

import java.util.Map;

/**
 * The single seam between the shared engine logic and the two low-level REST clients (Elastic /
 * OpenSearch). Operates on raw JSON request / response bodies so the parser, row-security applier,
 * result mapper, and introspector never depend on a driver type — the Elasticsearch / OpenSearch
 * analogue of {@code CassandraSessionManager} returning a {@code CqlSession}, but reduced to a
 * stateless HTTP call. Implementations translate driver failures into {@link
 * SearchTransportException}.
 */
interface SearchTransport extends AutoCloseable {

    /**
     * Perform one request. {@code params} become query-string parameters; {@code body} (nullable)
     * is sent with {@code contentType} ({@code application/json}, or {@code application/x-ndjson}
     * for {@code _bulk}). Returns the response body, or throws {@link SearchTransportException} on
     * an HTTP error / connect / timeout.
     */
    String perform(String method, String path, Map<String, String> params, String body,
                   String contentType);

    @Override
    void close();
}
