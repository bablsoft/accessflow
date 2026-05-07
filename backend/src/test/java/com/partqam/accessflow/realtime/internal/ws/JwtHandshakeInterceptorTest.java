package com.partqam.accessflow.realtime.internal.ws;

import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.security.api.AccessTokenAuthenticationException;
import com.partqam.accessflow.security.api.AccessTokenAuthenticator;
import com.partqam.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtHandshakeInterceptorTest {

    @Mock AccessTokenAuthenticator accessTokenAuthenticator;
    @Mock ServerHttpRequest request;
    @Mock ServerHttpResponse response;
    @Mock WebSocketHandler wsHandler;

    @InjectMocks JwtHandshakeInterceptor interceptor;

    @Test
    void rejectsHandshakeWhenQueryStringIsMissing() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws"));

        var attributes = new HashMap<String, Object>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    void rejectsHandshakeWhenTokenParamIsMissing() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws?other=foo"));

        var attributes = new HashMap<String, Object>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    void rejectsHandshakeWhenAuthenticatorThrows() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws?token=bad"));
        when(accessTokenAuthenticator.authenticate("bad"))
                .thenThrow(new AccessTokenAuthenticationException("invalid"));

        var attributes = new HashMap<String, Object>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    void storesClaimsOnSuccessfulHandshake() {
        var claims = new JwtClaims(UUID.randomUUID(), "alice@example.com",
                UserRoleType.ANALYST, UUID.randomUUID());
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws?token=good"));
        when(accessTokenAuthenticator.authenticate("good")).thenReturn(claims);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).containsEntry(JwtHandshakeInterceptor.ATTR_CLAIMS, claims);
    }

    @Test
    void afterHandshakeIsNoOp() {
        // Should not throw under any circumstance.
        interceptor.afterHandshake(request, response, wsHandler, new RuntimeException("boom"));
    }
}
