package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.internal.web.ApiKeyRequestFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultOnBehalfOfPrincipalServiceTest {

    private final DefaultOnBehalfOfPrincipalService service = new DefaultOnBehalfOfPrincipalService();

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void emptyOffTheRequestThread() {
        assertThat(service.current()).isEmpty();
    }

    @Test
    void emptyWhenTheRequestCarriesNoPrincipal() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(service.current()).isEmpty();
    }

    @Test
    void emptyWhenTheAttributeIsNotAUuid() {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyRequestFilter.ON_BEHALF_OF_ATTRIBUTE, "alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(service.current()).isEmpty();
    }

    @Test
    void returnsTheParkedPrincipal() {
        var alice = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyRequestFilter.ON_BEHALF_OF_ATTRIBUTE, alice);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(service.current()).contains(alice);
    }
}
