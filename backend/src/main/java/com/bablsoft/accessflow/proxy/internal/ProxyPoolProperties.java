package com.bablsoft.accessflow.proxy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("accessflow.proxy")
record ProxyPoolProperties(
        Duration connectionTimeout,
        Duration idleTimeout,
        Duration maxLifetime,
        Duration leakDetectionThreshold,
        String poolNamePrefix,
        Execution execution) {

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
        if (execution == null) {
            execution = new Execution(null, null, null, null, null, null, null);
        }
    }

    record Execution(Integer maxRows, Duration statementTimeout, Integer defaultFetchSize,
                     Integer insertBatchChunkSize, Long maxResultBytes, Integer maxConcurrent,
                     Duration acquireTimeout) {

        Execution {
            if (maxRows == null) {
                maxRows = 10_000;
            }
            if (statementTimeout == null) {
                statementTimeout = Duration.ofSeconds(30);
            }
            if (defaultFetchSize == null) {
                defaultFetchSize = 1_000;
            }
            if (insertBatchChunkSize == null) {
                insertBatchChunkSize = 1_000;
            }
            if (maxResultBytes == null) {
                maxResultBytes = 52_428_800L;
            }
            if (maxConcurrent == null) {
                maxConcurrent = 32;
            }
            if (acquireTimeout == null) {
                acquireTimeout = Duration.ofSeconds(5);
            }
        }
        // NOTE: no overloaded constructors here — @ConfigurationProperties record binding
        // silently degrades to all-defaults when the record has more than one constructor.
    }
}
