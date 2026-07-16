package com.bablsoft.accessflow.proxy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * SELECT result-cache tuning (AF-457). {@code enabled} is the deployment-wide kill-switch (the
 * real gate is each datasource's {@code result_cache_enabled} opt-in); {@code defaultTtl} applies
 * when a datasource opts in without its own {@code result_cache_ttl_seconds}; entries whose
 * serialized form exceeds {@code maxEntryBytes} are not cached.
 */
@ConfigurationProperties("accessflow.proxy.cache")
record ProxyCacheProperties(Boolean enabled, Duration defaultTtl, Long maxEntryBytes) {

    ProxyCacheProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (defaultTtl == null) {
            defaultTtl = Duration.ofSeconds(60);
        }
        if (maxEntryBytes == null) {
            maxEntryBytes = 1_000_000L;
        }
    }
}
