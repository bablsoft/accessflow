package com.bablsoft.accessflow.api.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the outbound HTTP client and the virtual-thread executor behind the release update check.
 * The {@code RestClient} bean is qualified and never {@code @Primary}: {@code notificationsRestClient}
 * owns by-type injection, so a second unqualified {@code RestClient} would make it ambiguous.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UpdateCheckProperties.class)
class ApiConfiguration {

    @Bean
    RestClient updateCheckRestClient(UpdateCheckProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean(destroyMethod = "close")
    ExecutorService updateCheckExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
