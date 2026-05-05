package com.partqam.accessflow.proxy.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyPoolProperties.class)
class ProxyConfiguration {

    @Bean
    Clock proxyClock() {
        return Clock.systemUTC();
    }
}
