package com.partqam.accessflow.ai.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiAnalyzerProperties.class)
class AiAnalyzerConfiguration {
}
