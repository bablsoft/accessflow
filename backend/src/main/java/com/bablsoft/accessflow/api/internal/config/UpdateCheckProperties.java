package com.bablsoft.accessflow.api.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Deployment-wide tunables for the release update check. The check fetches a static JSON manifest
 * ({@code version.json}) from {@link #url()} at most once per {@link #ttl()} and never sends
 * anything about the install. Every property is optional; a single compact constructor supplies
 * the defaults (a second constructor would silently unbind every property).
 */
@ConfigurationProperties("accessflow.updates")
public record UpdateCheckProperties(
        Boolean enabled,
        URI url,
        Duration ttl,
        Duration timeout) {

    static final URI DEFAULT_URL = URI.create("https://accessflow.io/version.json");
    static final Duration DEFAULT_TTL = Duration.ofHours(24);
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public UpdateCheckProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (url == null) {
            url = DEFAULT_URL;
        }
        ttl = positiveOrDefault(ttl, DEFAULT_TTL, "ttl");
        timeout = positiveOrDefault(timeout, DEFAULT_TIMEOUT, "timeout");
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("accessflow.updates." + name + " must be a positive duration");
        }
        return value;
    }
}
