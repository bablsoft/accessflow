package com.bablsoft.accessflow.security.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "accessflow.security.password-reset")
public record PasswordResetProperties(Duration ttl, URI resetBaseUrl) {

    public PasswordResetProperties {
        if (ttl == null) {
            ttl = Duration.ofHours(1);
        }
        if (resetBaseUrl == null) {
            resetBaseUrl = URI.create("http://localhost:5173");
        }
    }
}
