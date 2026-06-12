package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.SslMode;

import java.time.Duration;

/**
 * The resolved, driver-agnostic connection parameters for one datasource — scheme / host / port
 * (from a verbatim base-URL override or host/port/sslMode), the {@link EsAuth} strategy, the
 * {@link SslMode}, and the connect/socket timeouts. Deliberately holds primitives rather than a
 * driver {@code HttpHost} because the two flavours use different Apache HttpComponents major
 * versions (Elasticsearch 9.x → HttpClient 4 {@code org.apache.http}; OpenSearch 3.x → HttpClient 5
 * {@code org.apache.hc}); each transport builds its own host from these fields.
 */
record EsConnectionConfig(String scheme, String host, int port, EsAuth auth, SslMode sslMode,
                          Duration connectTimeout, Duration socketTimeout) {

    /** True when the channel is TLS and the policy is REQUIRE (encrypt without cert verification). */
    boolean trustAll() {
        return "https".equalsIgnoreCase(scheme) && sslMode == SslMode.REQUIRE;
    }
}
