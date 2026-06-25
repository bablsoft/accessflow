package com.bablsoft.accessflow.dashboard.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DashboardProperties.class)
class DashboardConfiguration {
}
