package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.ApplicationNameSource;
import com.bablsoft.accessflow.core.api.ClientApplication;
import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.RequestApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRequestApplicationServiceTest {

    private final DefaultRequestApplicationService service = new DefaultRequestApplicationService();
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyOffTheRequestThread() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken("reporting"));

        assertThat(service.current()).isEmpty();
    }

    @Test
    void emptyWhenNeitherKeyNorHeaderNamesOne() {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(null));

        assertThat(service.current()).isEmpty();
    }

    @Test
    void namedKeyIsTrustedAndBeatsTheHeader() {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken("  reporting  "));
        request.addHeader(RequestApplicationService.HEADER, "spoofed");

        assertThat(service.current())
                .contains(new ClientApplication("reporting", ApplicationNameSource.API_KEY));
    }

    @Test
    void unnamedKeyFallsBackToTheUntrustedHeader() {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(" "));
        request.addHeader(RequestApplicationService.HEADER, "etl-runner");

        var app = service.current().orElseThrow();
        assertThat(app).isEqualTo(new ClientApplication("etl-runner", ApplicationNameSource.HEADER));
        assertThat(app.trusted()).isFalse();
    }

    @Test
    void jwtSessionUsesTheHeader() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", null));
        request.addHeader(RequestApplicationService.HEADER, "notebook");

        assertThat(service.current())
                .contains(new ClientApplication("notebook", ApplicationNameSource.HEADER));
    }

    @Test
    void anonymousRequestUsesTheHeader() {
        request.addHeader(RequestApplicationService.HEADER, "cli");

        assertThat(service.current()).map(ClientApplication::source).contains(ApplicationNameSource.HEADER);
    }

    @Test
    void sanitizeDropsBlankAndControlCharactersAndTruncates() {
        assertThat(DefaultRequestApplicationService.sanitize(null)).isNull();
        assertThat(DefaultRequestApplicationService.sanitize("   ")).isNull();
        assertThat(DefaultRequestApplicationService.sanitize("bad\nvalue")).isNull();
        assertThat(DefaultRequestApplicationService.sanitize("tab\tvalue")).isNull();
        assertThat(DefaultRequestApplicationService.sanitize(" ok ")).isEqualTo("ok");
        var longName = "a".repeat(RequestApplicationService.MAX_LENGTH + 20);
        assertThat(DefaultRequestApplicationService.sanitize(longName))
                .hasSize(RequestApplicationService.MAX_LENGTH);
    }

    @Test
    void headerWithControlCharactersIsIgnored() {
        request.addHeader(RequestApplicationService.HEADER, "evil\u0007");

        assertThat(service.current()).isEmpty();
    }

    private static final class StubApiKeyToken extends AbstractAuthenticationToken
            implements ApiKeyAuthentication {

        private final String applicationName;

        StubApiKeyToken(String applicationName) {
            super(List.of());
            this.applicationName = applicationName;
            setAuthenticated(true);
        }

        @Override
        public UUID apiKeyId() {
            return UUID.randomUUID();
        }

        @Override
        public String applicationName() {
            return applicationName;
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return "bot";
        }
    }
}
