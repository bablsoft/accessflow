package com.partqam.accessflow.security.api;

/**
 * Thrown when an access token cannot be validated (malformed, signature invalid, expired, or
 * wrong type). Public so consumers outside the {@code security} module — including the
 * realtime WebSocket handshake — can react without depending on internal exception types.
 */
public class AccessTokenAuthenticationException extends RuntimeException {

    public AccessTokenAuthenticationException(String message) {
        super(message);
    }

    public AccessTokenAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
