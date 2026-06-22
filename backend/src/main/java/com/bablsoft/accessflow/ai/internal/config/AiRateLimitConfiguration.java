package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables the {@link AiRateLimitProperties} binding for the AI rate limit / cost budget (AF-55). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiRateLimitProperties.class)
class AiRateLimitConfiguration {
}
