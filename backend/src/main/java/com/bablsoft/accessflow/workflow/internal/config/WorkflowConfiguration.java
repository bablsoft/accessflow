package com.bablsoft.accessflow.workflow.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowProperties.class)
class WorkflowConfiguration {
}
