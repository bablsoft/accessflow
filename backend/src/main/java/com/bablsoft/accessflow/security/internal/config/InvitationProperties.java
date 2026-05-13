package com.bablsoft.accessflow.security.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "accessflow.security.invitation")
public record InvitationProperties(Duration ttl, URI acceptBaseUrl) {

    public InvitationProperties {
        if (ttl == null) {
            ttl = Duration.ofDays(7);
        }
        if (acceptBaseUrl == null) {
            acceptBaseUrl = URI.create("http://localhost:5173");
        }
    }
}
