package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wires the outbound HTTP client for Voyage AI embeddings (AF-918). Per-org credentials and the base
 * URL are supplied per call from the {@code ai_config} row, so the client itself holds only
 * timeouts. Qualified by bean name — {@code notifications} injects {@link RestClient} by type.
 */
@Configuration(proxyBeanMethods = false)
class VoyageConfiguration {

    // Fixed rather than bound to a property, matching auditSinkRestClient: these govern one vendor's
    // embeddings endpoint, and ai_config.timeout_ms is the analyzer's call budget, not this client's.
    // An embedding batch is slower than a chat completion, hence the generous read timeout.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    RestClient voyageRestClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }
}
