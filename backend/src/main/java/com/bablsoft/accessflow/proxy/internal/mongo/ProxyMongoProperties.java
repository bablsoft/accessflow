package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.api.MongoConnectionStringFactory.MongoClientOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MongoDB client tuning for the proxy engine. Mirrors the relational {@code accessflow.proxy.*}
 * HikariCP knobs for the native {@code MongoClient}.
 */
@ConfigurationProperties("accessflow.proxy.mongo")
public record ProxyMongoProperties(
        Duration connectTimeout,
        Duration serverSelectionTimeout,
        Integer maxPoolSize) {

    public ProxyMongoProperties {
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

    MongoClientOptions toOptions() {
        return new MongoClientOptions(connectTimeout, serverSelectionTimeout, maxPoolSize);
    }
}
