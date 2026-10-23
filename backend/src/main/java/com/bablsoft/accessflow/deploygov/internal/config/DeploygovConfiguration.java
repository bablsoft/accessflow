package com.bablsoft.accessflow.deploygov.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeploygovProperties.class)
class DeploygovConfiguration {
}
