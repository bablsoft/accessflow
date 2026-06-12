package com.bablsoft.accessflow.engine.cassandra;

import java.time.Duration;
import java.util.Map;

/**
 * Cassandra driver tuning parsed from the host-provided {@code QueryEngineContext.config()} map
 * (bound by the host from {@code accessflow.proxy.engines.cassandra.*} / {@code …scylladb.*} —
 * AF-418's generic per-engine lane; operators set {@code ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>} env
 * vars). Key names are the host&harr;plugin contract: {@code connect-timeout} (the driver's
 * connection-connect timeout) and {@code request-timeout} (the default per-request timeout; the
 * host overrides it per statement with the computed statement timeout) are ISO-8601 durations.
 * Missing or unparseable values fall back to the defaults rather than failing engine initialization.
 */
record CassandraEngineSettings(Duration connectTimeout, Duration requestTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    static CassandraEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new CassandraEngineSettings(
                duration(cfg.get("connect-timeout"), DEFAULT_CONNECT_TIMEOUT),
                duration(cfg.get("request-timeout"), DEFAULT_REQUEST_TIMEOUT));
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
