package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.engine.mongodb.MongoConnectionStringFactory.MongoClientOptions;

import java.time.Duration;
import java.util.Map;

/**
 * MongoDB client tuning parsed from the host-provided {@code QueryEngineContext.config()} map —
 * the plugin-side counterpart of the host's {@code MongoEngineProperties}
 * ({@code accessflow.proxy.mongo.*} / {@code ACCESSFLOW_PROXY_MONGO_*}). Key names are the
 * host&harr;plugin contract: {@code connect-timeout} and {@code server-selection-timeout} are
 * ISO-8601 durations, {@code max-pool-size} an integer. Missing or unparseable values fall back to
 * the pre-plugin defaults (10s / 10s / 10) rather than failing engine initialization.
 */
record MongoEngineSettings(
        Duration connectTimeout,
        Duration serverSelectionTimeout,
        int maxPoolSize) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_POOL_SIZE = 10;

    static MongoEngineSettings from(Map<String, String> config) {
        return new MongoEngineSettings(
                duration(config.get("connect-timeout")),
                duration(config.get("server-selection-timeout")),
                intValue(config.get("max-pool-size")));
    }

    MongoClientOptions toOptions() {
        return new MongoClientOptions(connectTimeout, serverSelectionTimeout, maxPoolSize);
    }

    private static Duration duration(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        try {
            return Duration.parse(raw);
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
