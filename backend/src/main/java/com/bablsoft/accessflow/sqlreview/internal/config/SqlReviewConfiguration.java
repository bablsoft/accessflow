package com.bablsoft.accessflow.sqlreview.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SqlReviewProperties.class)
class SqlReviewConfiguration {
}
