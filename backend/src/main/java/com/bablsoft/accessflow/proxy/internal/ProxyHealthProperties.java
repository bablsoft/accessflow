package com.bablsoft.accessflow.proxy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("accessflow.proxy.health")
record ProxyHealthProperties(Duration cacheTtl) {

    ProxyHealthProperties {
        if (cacheTtl == null) {
            cacheTtl = Duration.ofSeconds(30);
        }
    }
}
