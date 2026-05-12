package com.bablsoft.accessflow.realtime.internal.ws;

import com.bablsoft.accessflow.security.api.AccessTokenAuthenticationException;
import com.bablsoft.accessflow.security.api.AccessTokenAuthenticator;
import com.bablsoft.accessflow.security.api.JwtClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * Authenticates the WebSocket upgrade by validating the {@code ?token=<JWT>} query parameter.
 * Browsers cannot set custom headers on a WS handshake, so the standard
 * {@code Authorization: Bearer} pathway used by the REST filter chain isn't available here.
 * On success the resolved {@link JwtClaims} are stored under {@link #ATTR_CLAIMS} so the
 * handler can read the authenticated user without re-parsing the token.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class JwtHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_CLAIMS = "jwtClaims";
    private static final String QUERY_PARAM = "token=";

    private final AccessTokenAuthenticator accessTokenAuthenticator;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        var token = extractToken(request.getURI());
        if (token == null) {
            log.debug("WS handshake rejected: missing token query param");
            return false;
        }
        try {
            var claims = accessTokenAuthenticator.authenticate(token);
            attributes.put(ATTR_CLAIMS, claims);
            return true;
        } catch (AccessTokenAuthenticationException ex) {
            log.debug("WS handshake rejected: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    private static String extractToken(URI uri) {
        var query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (var pair : query.split("&")) {
            if (pair.startsWith(QUERY_PARAM)) {
                var raw = pair.substring(QUERY_PARAM.length());
                return raw.isBlank() ? null : raw;
            }
        }
        return null;
    }
}
