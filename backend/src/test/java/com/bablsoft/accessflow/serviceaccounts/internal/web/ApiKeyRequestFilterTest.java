package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.internal.OnBehalfOfResolver;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRateLimitExceededException;
import com.bablsoft.accessflow.serviceaccounts.internal.ServiceAccountRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyRequestFilterTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Mock ServiceAccountRateLimiter rateLimiter;
    @Mock OnBehalfOfResolver onBehalfOfResolver;
    @Mock MessageSource messageSource;

    // The Boot-configured mapper unwraps ProblemDetail properties through this mixin; mirror it.
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
    private final UUID userId = UUID.randomUUID();
    private final JwtClaims claims = new JwtClaims(userId, "bot@example.com", UserRoleType.READONLY,
            null, "READONLY", Set.of(), UUID.randomUUID(), false);

    private ApiKeyRequestFilter filter() {
        return new ApiKeyRequestFilter(rateLimiter, onBehalfOfResolver, new OnBehalfOfDecisionPaths(),
                objectMapper, messageSource, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /** The real token is package-private in {@code security}; the marker interface is the contract. */
    private static final class StubApiKeyToken extends AbstractAuthenticationToken implements ApiKeyAuthentication {
        private final JwtClaims principal;

        StubApiKeyToken(JwtClaims principal) {
            super(List.of());
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public UUID apiKeyId() {
            return UUID.randomUUID();
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }

    @Test
    void anonymousRequestPassesThroughWithoutTouchingTheLimiter() throws Exception {
        var chain = new MockFilterChain();
        filter().doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void jwtSessionIsNeverRateLimited() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(claims, null, "ROLE_USER"));
        var chain = new MockFilterChain();

        filter().doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void apiKeyRequestUnderTheLimitIsCountedAndContinues() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims));
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(new MockHttpServletRequest("POST", "/mcp"), response, chain);

        verify(rateLimiter).enforce(userId);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void apiKeyRequestOverTheLimitGets429WithRetryAfterAndProblemDetail() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims));
        doThrow(new ServiceAccountRateLimitExceededException(120, 17, "minute"))
                .when(rateLimiter).enforce(userId);
        when(messageSource.getMessage(eq("error.service_account_rate_limit_exceeded.minute"),
                any(Object[].class), eq(Locale.GERMANY))).thenReturn("Zu viele Anfragen");
        MDC.put("traceId", "abc123");
        var request = new MockHttpServletRequest("GET", "/api/v1/deployment-gate");
        request.addPreferredLocale(Locale.GERMANY);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("17");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        var json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("status").asInt()).isEqualTo(429);
        assertThat(json.get("detail").asString()).isEqualTo("Zu viele Anfragen");
        assertThat(json.get("error").asString()).isEqualTo("SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED");
        assertThat(json.get("limit").asInt()).isEqualTo(120);
        assertThat(json.get("retryAfterSeconds").asLong()).isEqualTo(17L);
        assertThat(json.get("timestamp").asString()).isEqualTo(NOW.toString());
        assertThat(json.get("traceId").asString()).isEqualTo("abc123");
    }

    @Test
    void dailyWindowResolvesTheDayMessageAndOmitsTraceIdWhenAbsent() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims));
        doThrow(new ServiceAccountRateLimitExceededException(5000, 3600, "day"))
                .when(rateLimiter).enforce(userId);
        when(messageSource.getMessage(eq("error.service_account_rate_limit_exceeded.day"),
                any(Object[].class), any(Locale.class))).thenReturn("Too many per day");
        var response = new MockHttpServletResponse();

        filter().doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), response, new MockFilterChain());

        var json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("detail").asString()).isEqualTo("Too many per day");
        assertThat(json.get("limit").asInt()).isEqualTo(5000);
        assertThat(json.has("traceId")).isFalse();
    }
    // ---- X-AccessFlow-On-Behalf-Of (#874) ----

    private static MockHttpServletRequest onBehalfOf(String method, String path, String value) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.addHeader(ApiKeyRequestFilter.ON_BEHALF_OF_HEADER, value);
        return request;
    }

    private void stubForbiddenMessage(String key) {
        when(messageSource.getMessage(eq(key), any(), any(Locale.class))).thenReturn("nope");
    }

    @Test
    void headerOnAnonymousRequestIsIgnored() throws Exception {
        // The security chain never disables anonymous auth, so a permitAll path carries an
        // AnonymousAuthenticationToken (isAuthenticated() == true) rather than an empty context.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(onBehalfOf("GET", "/actuator/health", "alice@example.com"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(onBehalfOfResolver);
    }

    @Test
    void headerOnJwtSessionIs403NotApiKey() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(claims, null, "ROLE_USER"));
        stubForbiddenMessage("error.on_behalf_of_not_permitted.not_api_key");
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(onBehalfOf("POST", "/api/v1/queries", "alice@example.com"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asString()).isEqualTo("ON_BEHALF_OF_NOT_PERMITTED");
        assertThat(body.get("reason").asString()).isEqualTo("not_api_key");
        assertThat(body.get("timestamp").asString()).isEqualTo(NOW.toString());
        verifyNoInteractions(onBehalfOfResolver);
    }

    @Test
    void headerOnDecisionPathIs403ReviewForbiddenBeforeResolution() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims));
        stubForbiddenMessage("error.on_behalf_of_review_forbidden");
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(onBehalfOf("POST", "/api/v1/reviews/" + UUID.randomUUID() + "/approve",
                "alice@example.com"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asString()).isEqualTo("ON_BEHALF_OF_REVIEW_FORBIDDEN");
        assertThat(body.has("reason")).isFalse();
        verifyNoInteractions(onBehalfOfResolver);
    }

    @Test
    void unresolvedPrincipalIs403NotPermittedAndNothingIsParked() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims));
        when(onBehalfOfResolver.resolve(eq(userId), eq(claims.organizationId()), eq("alice@example.com")))
                .thenReturn(Optional.empty());
        stubForbiddenMessage("error.on_behalf_of_not_permitted.not_permitted");
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        var request = onBehalfOf("POST", "/api/v1/queries", "alice@example.com");

        filter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(response.getContentAsString()).get("reason").asString())
                .isEqualTo("not_permitted");
        assertThat(request.getAttribute(ApiKeyRequestFilter.ON_BEHALF_OF_ATTRIBUTE)).isNull();
    }

    @Test
    void resolvedPrincipalIsParkedOnTheRequestAndTheAuthenticationIsUntouched() throws Exception {
        var token = new StubApiKeyToken(claims);
        SecurityContextHolder.getContext().setAuthentication(token);
        var alice = UUID.randomUUID();
        when(onBehalfOfResolver.resolve(eq(userId), eq(claims.organizationId()), eq(alice.toString())))
                .thenReturn(Optional.of(alice));
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        var request = onBehalfOf("POST", "/mcp", alice.toString());

        filter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(ApiKeyRequestFilter.ON_BEHALF_OF_ATTRIBUTE)).isEqualTo(alice);
        // The permission set can only ever be the key owner's: the Authentication is the same object.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(token);
        assertThat(token.getPrincipal()).isSameAs(claims);
        verify(rateLimiter).enforce(userId);
    }

    @Test
    void rateLimitWinsOverTheHeader() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims));
        doThrow(new ServiceAccountRateLimitExceededException(120, 17, "minute"))
                .when(rateLimiter).enforce(userId);
        when(messageSource.getMessage(eq("error.service_account_rate_limit_exceeded.minute"),
                any(), any(Locale.class))).thenReturn("slow down");
        var response = new MockHttpServletResponse();

        filter().doFilter(onBehalfOf("POST", "/api/v1/queries", "alice@example.com"), response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        verifyNoInteractions(onBehalfOfResolver);
    }
}
