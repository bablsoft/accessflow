package com.partqam.accessflow.realtime.internal.ws;

import com.partqam.accessflow.security.api.JwtClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@RequiredArgsConstructor
@Slf4j
class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private final SessionRegistry sessionRegistry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var claims = (JwtClaims) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_CLAIMS);
        if (claims == null) {
            // Should never happen — handshake interceptor returns false if it can't authenticate.
            log.warn("WS session {} opened without auth claims; closing", session.getId());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception ignored) {
                // best-effort
            }
            return;
        }
        sessionRegistry.register(claims.userId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var claims = (JwtClaims) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_CLAIMS);
        if (claims != null) {
            sessionRegistry.unregister(claims.userId(), session);
        }
    }
}
