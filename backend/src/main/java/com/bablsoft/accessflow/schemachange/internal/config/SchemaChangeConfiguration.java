package com.bablsoft.accessflow.schemachange.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaChangeProperties.class)
class SchemaChangeConfiguration {
}
