package com.bablsoft.accessflow.apigov.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires apigov outbound infrastructure. The {@code apigovOAuth2RestClient} posts to connector token
 * endpoints when sourcing outbound OAuth2 access tokens; it is qualifier-injected (never
 * {@code @Primary}) so it does not collide with {@code notificationsRestClient}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ApigovOAuth2Properties.class, ApigovRequestProperties.class})
class ApigovConfiguration {

    @Bean
    RestClient apigovOAuth2RestClient(ApigovOAuth2Properties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.oauth2TokenRequestTimeout());
        factory.setReadTimeout(properties.oauth2TokenRequestTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }
}
