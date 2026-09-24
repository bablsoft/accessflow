package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.UUID;

/**
 * Authentication token populated by {@link ApiKeyAuthenticationFilter}. Holds {@link JwtClaims} as
 * principal so downstream code (controllers, MCP tools) reads it identically to a JWT-issued token.
 * Implements the {@link ApiKeyAuthentication} marker so other modules can detect the API-key
 * channel (e.g. CI/CD-origin routing) without reaching into {@code security.internal}, and — since
 * #869 — which key was presented ({@link #apiKeyId()}). The key id is kept beside the claims,
 * never inside them, so permission resolution can never read it.
 */
class ApiKeyAuthenticationToken extends AbstractAuthenticationToken implements ApiKeyAuthentication {

    private final UUID apiKeyId;
    private final String applicationName;
    private final JwtClaims claims;

    ApiKeyAuthenticationToken(UUID apiKeyId, JwtClaims claims) {
        this(apiKeyId, null, claims);
    }

    ApiKeyAuthenticationToken(UUID apiKeyId, String applicationName, JwtClaims claims) {
        super(JwtAuthorities.from(claims));
        this.apiKeyId = apiKeyId;
        this.applicationName = applicationName;
        this.claims = claims;
        setAuthenticated(true);
    }

    @Override
    public UUID apiKeyId() {
        return apiKeyId;
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
    public JwtClaims getPrincipal() {
        return claims;
    }
}
