package com.bablsoft.accessflow.core.api;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Host capabilities handed to a {@link QueryEngine} in {@link
 * QueryEngine#initialize(QueryEngineContext)} — the engine-plugin SDK's replacement for Spring
 * dependency injection, since plugins are loaded into a plain classloader with no application
 * context. {@code config} carries the engine's tuning knobs as plain strings (for the MongoDB
 * engine: {@code connect-timeout} / {@code server-selection-timeout} as ISO-8601 durations and
 * {@code max-pool-size}), bound by the host from its own configuration namespace so operator env
 * vars keep working unchanged.
 */
public record QueryEngineContext(EngineMessages messages,
                                 CredentialDecryptor credentials,
                                 Map<String, String> config,
                                 Clock clock) {

    public QueryEngineContext {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(credentials, "credentials must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
