package com.bablsoft.accessflow.proxy.internal.driver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * MongoDB engine tuning, bound by the host and handed to the engine plugin as the plain-string
 * {@code config} map of its {@code QueryEngineContext}. Keeps the pre-plugin
 * {@code ACCESSFLOW_PROXY_MONGO_*} operator knobs working unchanged now that the engine itself
 * (which used to bind these directly as {@code ProxyMongoProperties}) lives outside the Spring
 * context.
 */
@ConfigurationProperties("accessflow.proxy.mongo")
public record MongoEngineProperties(
        Duration connectTimeout,
        Duration serverSelectionTimeout,
        Integer maxPoolSize) {

    public MongoEngineProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (serverSelectionTimeout == null) {
            serverSelectionTimeout = Duration.ofSeconds(10);
        }
        if (maxPoolSize == null) {
            maxPoolSize = 10;
        }
    }

    /** Key names are the engine-plugin contract — see the MongoDB engine's {@code MongoEngineSettings}. */
    Map<String, String> toEngineConfig() {
        return Map.of(
                "connect-timeout", connectTimeout.toString(),
                "server-selection-timeout", serverSelectionTimeout.toString(),
                "max-pool-size", Integer.toString(maxPoolSize));
    }
}
