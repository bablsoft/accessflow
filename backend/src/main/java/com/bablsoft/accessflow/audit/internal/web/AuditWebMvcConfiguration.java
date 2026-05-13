package com.bablsoft.accessflow.audit.internal.web;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
class AuditWebMvcConfiguration implements WebMvcConfigurer {

    private final RequestAuditContextArgumentResolver requestAuditContextArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(requestAuditContextArgumentResolver);
    }
}
