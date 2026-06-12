package com.bablsoft.accessflow.engine.dynamodb;

import java.time.Duration;
import java.util.Map;

/**
 * DynamoDB client tuning parsed from the host-provided {@code QueryEngineContext.config()} map
 * (bound by the host from {@code accessflow.proxy.engines.dynamodb.*} — AF-418's generic per-engine
 * lane; operators set {@code ACCESSFLOW_PROXY_ENGINES_DYNAMODB_<KEY>} env vars). Key names are the
 * host&harr;plugin contract: {@code connect-timeout} (the url-connection HTTP client's TCP connect
 * timeout) and {@code api-call-timeout} (the default per-request timeout; the host overrides it per
 * statement with the computed statement timeout) are ISO-8601 durations. Missing or unparseable
 * values fall back to the defaults rather than failing engine initialization.
 */
record DynamoDbEngineSettings(Duration connectTimeout, Duration apiCallTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_API_CALL_TIMEOUT = Duration.ofSeconds(30);

    static DynamoDbEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new DynamoDbEngineSettings(
                duration(cfg.get("connect-timeout"), DEFAULT_CONNECT_TIMEOUT),
                duration(cfg.get("api-call-timeout"), DEFAULT_API_CALL_TIMEOUT));
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
