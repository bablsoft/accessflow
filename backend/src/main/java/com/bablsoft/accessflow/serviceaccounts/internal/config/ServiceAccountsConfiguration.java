package com.bablsoft.accessflow.serviceaccounts.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAccountsProperties.class)
class ServiceAccountsConfiguration {
}
