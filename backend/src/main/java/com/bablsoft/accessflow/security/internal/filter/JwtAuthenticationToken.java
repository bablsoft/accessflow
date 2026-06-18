package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.security.api.JwtClaims;
import org.springframework.security.authentication.AbstractAuthenticationToken;

class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final JwtClaims claims;

    JwtAuthenticationToken(JwtClaims claims) {
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
