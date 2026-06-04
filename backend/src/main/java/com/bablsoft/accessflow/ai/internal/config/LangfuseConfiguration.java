package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the Langfuse outbound HTTP client and the virtual-thread executor used to post traces
 * off the request thread. Per-org credentials are supplied per call from {@code langfuse_config}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LangfuseProperties.class)
class LangfuseConfiguration {

    @Bean
    RestClient langfuseRestClient(LangfuseProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService langfuseTracingExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
