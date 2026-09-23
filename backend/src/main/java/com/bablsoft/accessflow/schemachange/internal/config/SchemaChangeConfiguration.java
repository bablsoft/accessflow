package com.bablsoft.accessflow.schemachange.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaChangeProperties.class)
class SchemaChangeConfiguration {

    /** Runs on-demand "Scan now" drift scans off the request thread (virtual threads, #881). */
    @Bean(destroyMethod = "close")
    ExecutorService schemaDriftScanExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
