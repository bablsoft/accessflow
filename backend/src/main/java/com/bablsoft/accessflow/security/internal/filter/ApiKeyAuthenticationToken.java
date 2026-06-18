package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Authentication token populated by {@link ApiKeyAuthenticationFilter}. Holds {@link JwtClaims} as
 * principal so downstream code (controllers, MCP tools) reads it identically to a JWT-issued token.
 * Implements the {@link ApiKeyAuthentication} marker so other modules can detect the API-key
 * channel (e.g. CI/CD-origin routing) without reaching into {@code security.internal}.
 */
class ApiKeyAuthenticationToken extends AbstractAuthenticationToken implements ApiKeyAuthentication {

    private final JwtClaims claims;

    ApiKeyAuthenticationToken(JwtClaims claims) {
        super(JwtAuthorities.from(claims));
        this.claims = claims;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public JwtClaims getPrincipal() {
        return claims;
    }
}
