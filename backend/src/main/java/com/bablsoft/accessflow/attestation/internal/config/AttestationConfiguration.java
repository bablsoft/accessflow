package com.bablsoft.accessflow.attestation.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// @EnableScheduling is enabled globally by the scheduling module; this module only contributes
// @Scheduled jobs and its configuration properties.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttestationProperties.class)
class AttestationConfiguration {
}
