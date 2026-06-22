package com.bablsoft.accessflow.security.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Step-up authentication tuning (AF-444). {@code ttl} bounds how long a freshly minted, single-use
 * step-up token stays valid in Redis before the action endpoint must reject it.
 */
@ConfigurationProperties(prefix = "accessflow.security.step-up")
public record StepUpProperties(Duration ttl) {

    public StepUpProperties {
        if (ttl == null) {
            ttl = Duration.ofMinutes(5);
        }
    }
}
