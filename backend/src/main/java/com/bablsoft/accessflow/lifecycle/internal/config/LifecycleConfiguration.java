package com.bablsoft.accessflow.lifecycle.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LifecycleProperties.class)
class LifecycleConfiguration {
}
