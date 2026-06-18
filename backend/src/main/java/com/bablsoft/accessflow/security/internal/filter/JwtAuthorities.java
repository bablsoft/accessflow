package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.security.api.JwtClaims;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Spring Security authority list for a {@link JwtClaims} principal. Every principal gets
 * its home-org role as {@code ROLE_<role>}; a platform admin (AF-456) additionally gets the
 * orthogonal {@code PLATFORM_ADMIN} authority that gates the cross-org management endpoints.
 */
final class JwtAuthorities {

    static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private JwtAuthorities() {
    }

    static List<GrantedAuthority> from(JwtClaims claims) {
        List<GrantedAuthority> authorities = new ArrayList<>(2);
        authorities.add(new SimpleGrantedAuthority("ROLE_" + claims.role().name()));
        if (claims.platformAdmin()) {
            authorities.add(new SimpleGrantedAuthority(PLATFORM_ADMIN));
        }
        return List.copyOf(authorities);
    }
}
