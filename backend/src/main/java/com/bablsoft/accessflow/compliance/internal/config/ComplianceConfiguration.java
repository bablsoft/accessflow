package com.bablsoft.accessflow.compliance.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ComplianceProperties.class)
class ComplianceConfiguration {
}
