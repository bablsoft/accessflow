package com.bablsoft.accessflow.engine.elasticsearch;

import java.time.Duration;
import java.util.Map;

/**
 * REST-client tuning parsed from the host-provided {@code QueryEngineContext.config()} map (bound by
 * the host from {@code accessflow.proxy.engines.elasticsearch.*} / {@code …opensearch.*} — AF-418's
 * generic per-engine lane; operators set {@code ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>} env vars). Key
 * names are the host&harr;plugin contract: {@code connect-timeout} (TCP connect) and
 * {@code socket-timeout} (per-request socket read; bounds latency) are ISO-8601 durations. Missing
 * or unparseable values fall back to the defaults rather than failing engine initialization. Admin
 * probes (connection test / introspection) use tighter fixed timeouts so a misconfigured datasource
 * fails fast in the UI.
 */
record ElasticsearchEngineSettings(Duration connectTimeout, Duration socketTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ADMIN_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ADMIN_SOCKET_TIMEOUT = Duration.ofSeconds(5);

    static ElasticsearchEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new ElasticsearchEngineSettings(
                duration(cfg.get("connect-timeout"), DEFAULT_CONNECT_TIMEOUT),
                duration(cfg.get("socket-timeout"), DEFAULT_SOCKET_TIMEOUT));
    }

    Duration adminConnectTimeout() {
        return ADMIN_CONNECT_TIMEOUT;
    }

    Duration adminSocketTimeout() {
        return ADMIN_SOCKET_TIMEOUT;
    }

    private static Duration duration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            var parsed = Duration.parse(raw);
            return parsed.isNegative() || parsed.isZero() ? fallback : parsed;
        } catch (java.time.format.DateTimeParseException e) {
            return fallback;
        }
    }
}
