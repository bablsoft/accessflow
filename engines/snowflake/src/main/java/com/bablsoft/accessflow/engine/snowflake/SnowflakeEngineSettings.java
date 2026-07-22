package com.bablsoft.accessflow.engine.snowflake;

import java.time.Duration;
import java.util.Map;

/**
 * Snowflake connection tuning parsed from the host-provided {@code QueryEngineContext.config()}
 * map (bound by the host from {@code accessflow.proxy.engines.snowflake.*} — AF-418's generic
 * per-engine lane; operators set {@code ACCESSFLOW_PROXY_ENGINES_SNOWFLAKE_<KEY>} env vars). Key
 * names are the host&harr;plugin contract: {@code login-timeout} (the driver's login/connect
 * timeout) and {@code network-timeout} (the socket-level network timeout bounding any single
 * roundtrip) are ISO-8601 durations. Missing or unparseable values fall back to the defaults
 * rather than failing engine initialization.
 */
record SnowflakeEngineSettings(Duration loginTimeout, Duration networkTimeout) {

    private static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_NETWORK_TIMEOUT = Duration.ofSeconds(60);

    static SnowflakeEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new SnowflakeEngineSettings(
                duration(cfg.get("login-timeout"), DEFAULT_LOGIN_TIMEOUT),
                duration(cfg.get("network-timeout"), DEFAULT_NETWORK_TIMEOUT));
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
