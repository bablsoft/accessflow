package com.bablsoft.accessflow.proxy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Read-replica health-check tuning (AF-457). {@code probeInterval} is the cadence of the per-node
 * background prober, {@code probeTimeout} the JDBC {@code isValid} timeout per endpoint, and
 * {@code cooldown} how long a failed endpoint sits out of the read rotation before a half-open
 * retry.
 */
@ConfigurationProperties("accessflow.proxy.replica")
record ProxyReplicaProperties(Duration probeInterval, Duration probeTimeout, Duration cooldown) {

    ProxyReplicaProperties {
        if (probeInterval == null) {
            probeInterval = Duration.ofSeconds(30);
        }
        if (probeTimeout == null) {
            probeTimeout = Duration.ofSeconds(5);
        }
        if (cooldown == null) {
            cooldown = Duration.ofSeconds(30);
        }
    }
}
