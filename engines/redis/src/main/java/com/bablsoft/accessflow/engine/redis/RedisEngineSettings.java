package com.bablsoft.accessflow.engine.redis;

import java.time.Duration;
import java.util.Map;

/**
 * Jedis client tuning parsed from the host-provided {@code QueryEngineContext.config()} map (bound
 * by the host from {@code accessflow.proxy.engines.redis.*} / {@code ACCESSFLOW_PROXY_ENGINES_REDIS_*},
 * AF-418). Key names are the host&harr;plugin contract: {@code connect-timeout} and
 * {@code socket-timeout} are ISO-8601 durations, {@code max-pool-size} an integer. Missing or
 * unparseable values fall back to the defaults (5s / 5s / 10) rather than failing engine
 * initialization.
 */
record RedisEngineSettings(Duration connectTimeout, Duration socketTimeout, int maxPoolSize) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_POOL_SIZE = 10;

    static RedisEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new RedisEngineSettings(
                duration(cfg.get("connect-timeout")),
                duration(cfg.get("socket-timeout")),
                intValue(cfg.get("max-pool-size")));
    }

    int connectTimeoutMillis() {
        return (int) Math.max(1, connectTimeout.toMillis());
    }

    int socketTimeoutMillis() {
        return (int) Math.max(1, socketTimeout.toMillis());
    }

    private static Duration duration(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        try {
            return Duration.parse(raw.strip());
        } catch (java.time.format.DateTimeParseException e) {
            return DEFAULT_TIMEOUT;
        }
    }

    private static int intValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_POOL_SIZE;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_POOL_SIZE;
        }
    }
}
