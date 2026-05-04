package com.partqam.accessflow.security.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "accessflow.jwt")
public record JwtProperties(
        String privateKey,
        Duration accessTokenExpiry,
        Duration refreshTokenExpiry
) {}
