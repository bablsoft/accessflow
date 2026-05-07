package com.partqam.accessflow.security.api;

/**
 * Public boundary for validating an access token from outside the {@code security} module.
 * Other modules (e.g. {@code realtime}) call this when they need to authenticate a request
 * that is not handled by the standard HTTP filter chain — most notably the WebSocket
 * handshake, where browsers cannot set a custom {@code Authorization} header on the upgrade.
 */
public interface AccessTokenAuthenticator {

    /**
     * Validates {@code token} as an access token and returns the embedded claims. Throws
     * {@link AccessTokenAuthenticationException} if the token is missing, malformed, expired,
     * has the wrong type, or has an invalid signature.
     */
    JwtClaims authenticate(String token);
}
