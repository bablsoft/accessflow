package com.partqam.accessflow.core.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "accessflow")
public record EncryptionProperties(String encryptionKey) {}
