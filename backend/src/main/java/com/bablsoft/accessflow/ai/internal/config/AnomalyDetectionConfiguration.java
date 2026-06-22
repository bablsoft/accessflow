package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables the {@link AnomalyDetectionProperties} binding for UBA (AF-383). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnomalyDetectionProperties.class)
class AnomalyDetectionConfiguration {
}
