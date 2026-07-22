package com.bablsoft.accessflow.engine.databricks;

import java.time.Duration;
import java.util.Map;

/**
 * Databricks client tuning parsed from the host-provided {@code QueryEngineContext.config()} map
 * (bound by the host from {@code accessflow.proxy.engines.databricks.*} — AF-418's generic
 * per-engine lane; operators set {@code ACCESSFLOW_PROXY_ENGINES_DATABRICKS_<KEY>} env vars). Key
 * names are the host&harr;plugin contract, all ISO-8601 durations: {@code connect-timeout} (the
 * JDK HttpClient's TCP connect timeout, default {@code PT10S}), {@code wait-timeout} (the
 * Statement Execution API's server-side {@code wait_timeout} hybrid-wait window, default
 * {@code PT10S}, clamped to the API's allowed 5–50 s and formatted {@code "10s"}), and
 * {@code poll-interval} (the client-side cadence between status GETs while a statement is
 * {@code PENDING}/{@code RUNNING}, default {@code PT1S}). Missing or unparseable values fall back
 * to the defaults rather than failing engine initialization.
 */
record DatabricksEngineSettings(Duration connectTimeout, Duration waitTimeout,
                                Duration pollInterval) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final long MIN_WAIT_SECONDS = 5;
    private static final long MAX_WAIT_SECONDS = 50;

    static DatabricksEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new DatabricksEngineSettings(
                duration(cfg.get("connect-timeout"), DEFAULT_CONNECT_TIMEOUT),
                clampWait(duration(cfg.get("wait-timeout"), DEFAULT_WAIT_TIMEOUT)),
                duration(cfg.get("poll-interval"), DEFAULT_POLL_INTERVAL));
    }

    /** The {@code wait_timeout} request value the API expects, e.g. {@code "10s"}. */
    String waitTimeoutValue() {
        return waitTimeout.toSeconds() + "s";
    }

    private static Duration clampWait(Duration wait) {
        long seconds = Math.clamp(wait.toSeconds(), MIN_WAIT_SECONDS, MAX_WAIT_SECONDS);
        return Duration.ofSeconds(seconds);
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
