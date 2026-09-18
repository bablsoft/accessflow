package com.bablsoft.accessflow.serviceaccounts.internal.config;

import com.bablsoft.accessflow.serviceaccounts.internal.ServiceAccountRateLimiter;
import com.bablsoft.accessflow.serviceaccounts.internal.web.ApiKeyRequestFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ServiceAccountsProperties.class, ServiceAccountRateLimitProperties.class})
class ServiceAccountsConfiguration {

    /**
     * Order at which the API-key rate-limit filter joins the servlet chain (#873). Spring Security's
     * {@code DelegatingFilterProxy} registers at {@code spring.security.filter.order}'s default,
     * {@code -100}, so {@code 0} runs inside it — after authentication and authorization, before the
     * {@code SecurityContext} is cleared. Registering here rather than in the security module keeps
     * the module graph acyclic.
     */
    static final int API_KEY_REQUEST_FILTER_ORDER = 0;

    @Bean
    FilterRegistrationBean<ApiKeyRequestFilter> apiKeyRequestFilter(ServiceAccountRateLimiter rateLimiter,
                                                                    ObjectMapper objectMapper,
                                                                    MessageSource messageSource,
                                                                    Clock clock) {
        var registration = new FilterRegistrationBean<>(
                new ApiKeyRequestFilter(rateLimiter, objectMapper, messageSource, clock));
        registration.setName("apiKeyRequestFilter");
        registration.setOrder(API_KEY_REQUEST_FILTER_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }
}
