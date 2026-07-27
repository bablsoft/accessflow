package com.bablsoft.accessflow.discovery.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiscoveryProperties.class)
class DiscoveryConfiguration {

    /** Runs on-demand "Scan now" requests off the request thread (virtual threads, AF-623). */
    @Bean(destroyMethod = "close")
    ExecutorService discoveryScanExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
