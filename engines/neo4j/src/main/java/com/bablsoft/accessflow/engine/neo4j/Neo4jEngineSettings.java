package com.bablsoft.accessflow.engine.neo4j;

import java.time.Duration;
import java.util.Map;

/**
 * Neo4j driver tuning parsed from the host-provided {@code QueryEngineContext.config()} map (bound
 * by the host from {@code accessflow.proxy.engines.neo4j.*} — AF-418's generic per-engine lane;
 * operators set {@code ACCESSFLOW_PROXY_ENGINES_NEO4J_<KEY>} env vars). Key names are the
 * host&harr;plugin contract: {@code connect-timeout} (the driver's connection-acquisition /
 * connect timeout, an ISO-8601 duration) and {@code max-connection-pool-size} (the driver's
 * internal connection-pool ceiling). Missing or unparseable values fall back to the defaults rather
 * than failing engine initialization.
 */
record Neo4jEngineSettings(Duration connectTimeout, int maxConnectionPoolSize) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_POOL_SIZE = 100;

    static Neo4jEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new Neo4jEngineSettings(
                duration(cfg.get("connect-timeout"), DEFAULT_CONNECT_TIMEOUT),
                positiveInt(cfg.get("max-connection-pool-size"), DEFAULT_MAX_POOL_SIZE));
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

    private static int positiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
