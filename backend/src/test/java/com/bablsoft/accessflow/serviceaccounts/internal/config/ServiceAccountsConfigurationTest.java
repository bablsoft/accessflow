package com.bablsoft.accessflow.serviceaccounts.internal.config;

import com.bablsoft.accessflow.serviceaccounts.internal.ServiceAccountRateLimiter;
import com.bablsoft.accessflow.serviceaccounts.internal.web.ApiKeyRequestFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceAccountsConfigurationTest {

    /**
     * Pins the servlet-order claim the module relies on: Spring Security's filter proxy registers at
     * {@code -100}, so {@code 0} places the rate-limit filter inside the security chain, and the
     * filter is mapped to every path on the REQUEST dispatch only.
     */
    @Test
    void apiKeyRequestFilterRunsInsideTheSecurityChainOnRequestDispatchOnly() throws Exception {
        var registration = new ServiceAccountsConfiguration().apiKeyRequestFilter(
                mock(ServiceAccountRateLimiter.class), new ObjectMapper(),
                mock(MessageSource.class), Clock.systemUTC());
        var servletContext = mock(ServletContext.class);
        var dynamic = mock(FilterRegistration.Dynamic.class);
        when(servletContext.addFilter(eq("apiKeyRequestFilter"), any(ApiKeyRequestFilter.class)))
                .thenReturn(dynamic);

        registration.onStartup(servletContext);

        assertThat(registration.getOrder()).isZero().isGreaterThan(-100);
        assertThat(registration.getFilter()).isInstanceOf(ApiKeyRequestFilter.class);
        verify(dynamic).addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
    }
}
