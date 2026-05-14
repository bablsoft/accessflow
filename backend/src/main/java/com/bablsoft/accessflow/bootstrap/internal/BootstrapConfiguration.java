package com.bablsoft.accessflow.bootstrap.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BootstrapProperties.class)
class BootstrapConfiguration {
}
