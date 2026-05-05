package com.partqam.accessflow.proxy.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyPoolProperties.class)
class ProxyConfiguration {
}
