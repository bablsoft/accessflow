package com.bablsoft.accessflow.apigov.api;

/**
 * Wire protocol of a governed API connector. {@code REST} (HTTP+JSON), {@code SOAP} (HTTP+XML),
 * {@code GRAPHQL} (HTTP+JSON query/mutation), and {@code GRPC} (HTTP/2 over a parsed proto
 * descriptor).
 */
public enum ApiProtocol {
    REST,
    SOAP,
    GRAPHQL,
    GRPC
}
