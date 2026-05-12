package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.proxy.internal.driver.DriverProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProxyPoolProperties.class, DriverProperties.class})
class ProxyConfiguration {

    @Bean
    Clock proxyClock() {
        return Clock.systemUTC();
    }
}
