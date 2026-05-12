package com.bablsoft.accessflow.security.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "accessflow.cors")
public record CorsProperties(String allowedOrigin) {}
