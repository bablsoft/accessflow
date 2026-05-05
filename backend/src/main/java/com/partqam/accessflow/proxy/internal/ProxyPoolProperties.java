package com.partqam.accessflow.proxy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("accessflow.proxy")
record ProxyPoolProperties(
        Duration connectionTimeout,
        Duration idleTimeout,
        Duration maxLifetime,
        Duration leakDetectionThreshold,
        String poolNamePrefix) {

    ProxyPoolProperties {
        if (connectionTimeout == null) {
            connectionTimeout = Duration.ofSeconds(30);
        }
        if (idleTimeout == null) {
            idleTimeout = Duration.ofMinutes(10);
        }
        if (maxLifetime == null) {
            maxLifetime = Duration.ofMinutes(30);
        }
        if (leakDetectionThreshold == null) {
            leakDetectionThreshold = Duration.ZERO;
        }
        if (poolNamePrefix == null) {
            poolNamePrefix = "accessflow-ds-";
        }
    }
}
