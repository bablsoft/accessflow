package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.proxy.internal.driver.DriverProperties;
import com.bablsoft.accessflow.proxy.internal.mongo.ProxyMongoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProxyPoolProperties.class, DriverProperties.class,
        ProxyHealthProperties.class, ProxyMongoProperties.class})
class ProxyConfiguration {

    /** Spring cache holding per-{@code (organizationId, datasourceId)} health snapshots. */
    static final String DATASOURCE_HEALTH_CACHE = "datasourceHealth";

    @Bean
    Clock proxyClock() {
        return Clock.systemUTC();
    }

    @Bean
    CacheManager datasourceHealthCacheManager(ProxyHealthProperties properties) {
        var cacheManager = new CaffeineCacheManager(DATASOURCE_HEALTH_CACHE);
        cacheManager.setCacheSpecification(
                "expireAfterWrite=" + properties.cacheTtl().toSeconds() + "s");
        return cacheManager;
    }
}
