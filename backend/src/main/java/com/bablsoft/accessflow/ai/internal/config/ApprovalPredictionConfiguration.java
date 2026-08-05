package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables the {@link ApprovalPredictionProperties} binding for approval prediction (AF-645). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalPredictionProperties.class)
class ApprovalPredictionConfiguration {
}
