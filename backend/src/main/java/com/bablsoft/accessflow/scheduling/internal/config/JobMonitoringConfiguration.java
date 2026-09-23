package com.bablsoft.accessflow.scheduling.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobMonitoringProperties.class)
class JobMonitoringConfiguration {
}
