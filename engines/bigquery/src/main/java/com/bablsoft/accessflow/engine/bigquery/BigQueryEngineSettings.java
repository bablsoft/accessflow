package com.bablsoft.accessflow.engine.bigquery;

import java.time.Duration;
import java.util.Map;

/**
 * BigQuery client tuning parsed from the host-provided {@code QueryEngineContext.config()} map
 * (bound by the host from {@code accessflow.proxy.engines.bigquery.*} — AF-418's generic
 * per-engine lane; operators set {@code ACCESSFLOW_PROXY_ENGINES_BIGQUERY_<KEY>} env vars). Key
 * names are the host&harr;plugin contract: {@code connect-timeout} (the HTTP transport's TCP
 * connect timeout) and {@code read-timeout} (the socket-read timeout per HTTP call) are ISO-8601
 * durations. Missing or unparseable values fall back to the defaults rather than failing engine
 * initialization.
 */
record BigQueryEngineSettings(Duration connectTimeout, Duration readTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    static BigQueryEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new BigQueryEngineSettings(
                duration(cfg.get("connect-timeout"), DEFAULT_CONNECT_TIMEOUT),
                duration(cfg.get("read-timeout"), DEFAULT_READ_TIMEOUT));
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
