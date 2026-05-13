package com.bablsoft.accessflow.mcp.internal.auth;

import com.bablsoft.accessflow.security.api.JwtClaims;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authentication token populated by {@link ApiKeyAuthenticationFilter}. Holds {@link JwtClaims} as
 * principal so downstream code (controllers, MCP tools) reads it identically to a JWT-issued token.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final JwtClaims claims;

    public ApiKeyAuthenticationToken(JwtClaims claims) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name())));
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
