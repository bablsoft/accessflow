package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Deployment-wide Langfuse tunables. Per-organization credentials and toggles live in the
 * {@code langfuse_config} table; these only cover the default host (pre-filled when a config omits
 * one), the prompt-fetch cache TTL, and the outbound HTTP timeouts.
 */
@ConfigurationProperties("accessflow.langfuse")
public record LangfuseProperties(
        URI defaultHost,
        Duration promptCacheTtl,
        Duration connectTimeout,
        Duration requestTimeout) {

    private static final URI DEFAULT_HOST = URI.create("https://cloud.langfuse.com/");

    public LangfuseProperties {
        defaultHost = normalizeBaseUrl(defaultHost == null ? DEFAULT_HOST : defaultHost);
        if (promptCacheTtl == null) {
            promptCacheTtl = Duration.ofSeconds(60);
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(10);
        }
    }

    /** Ensures a base URL ends in a single trailing slash so {@code URI.resolve} appends cleanly. */
    static URI normalizeBaseUrl(URI uri) {
        var raw = uri.toString();
        return raw.endsWith("/") ? uri : URI.create(raw + "/");
    }
}
