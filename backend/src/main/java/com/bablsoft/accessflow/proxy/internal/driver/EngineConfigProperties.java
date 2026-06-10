package com.bablsoft.accessflow.proxy.internal.driver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-engine plugin tuning, bound from {@code accessflow.proxy.engines.<connector-id>.*} and
 * handed verbatim (after key normalization) to the plugin as the plain-string {@code config} map
 * of its {@code QueryEngineContext}. The keys are each engine's own contract; the host never
 * interprets them. Generic env-var form: {@code ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>}. Plugins own
 * their defaults, so an absent engine entry yields an empty map.
 *
 * <p>Relaxed env binding delivers {@code ACCESSFLOW_PROXY_ENGINES_MONGODB_CONNECT_TIMEOUT} as the
 * inner map key {@code connect.timeout}; {@link #forEngine(String)} normalizes every key
 * (lowercase, {@code '.'}/{@code '_'} → {@code '-'}) so it lands on the contract key
 * {@code connect-timeout}. On collision after normalization, a non-canonical raw key (env-derived)
 * overrides a canonical one (YAML default) — env vars beat {@code application.yml}.
 */
@ConfigurationProperties("accessflow.proxy")
public record EngineConfigProperties(Map<String, Map<String, String>> engines) {

    public EngineConfigProperties {
        engines = engines == null ? Map.of() : engines;
    }

    /** Normalized config map for one engine id; empty when none is declared. */
    public Map<String, String> forEngine(String engineId) {
        var raw = engines.get(engineId);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        var normalized = new LinkedHashMap<String, String>();
        for (var entry : raw.entrySet()) {
            var key = normalize(entry.getKey());
            if (entry.getKey().equals(key)) {
                normalized.putIfAbsent(key, entry.getValue());
            } else {
                normalized.put(key, entry.getValue());
            }
        }
        return Map.copyOf(normalized);
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace('.', '-').replace('_', '-');
    }
}
