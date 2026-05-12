package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.security.api.JwtClaims;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final JwtClaims claims;

    JwtAuthenticationToken(JwtClaims claims) {
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
