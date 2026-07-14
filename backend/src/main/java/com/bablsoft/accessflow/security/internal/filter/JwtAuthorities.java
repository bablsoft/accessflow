package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.security.api.JwtClaims;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Spring Security authority list for a {@link JwtClaims} principal. Every principal
 * gets one {@code PERM_<name>} authority per resolved functional permission (AF-522) — the
 * surface {@code @PreAuthorize("hasAuthority('PERM_…')")} checks against. A principal on a system
 * role additionally keeps the legacy {@code ROLE_<role>} authority for transition compatibility;
 * a platform admin (AF-456) gets the orthogonal {@code PLATFORM_ADMIN} authority that gates the
 * cross-org management endpoints.
 */
final class JwtAuthorities {

    static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    static final String PERMISSION_PREFIX = "PERM_";

    private JwtAuthorities() {
    }

    static List<GrantedAuthority> from(JwtClaims claims) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (claims.permissions() != null) {
            for (var permission : claims.permissions()) {
                authorities.add(new SimpleGrantedAuthority(PERMISSION_PREFIX + permission.name()));
            }
        }
        if (claims.role() != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + claims.role().name()));
        }
        if (claims.platformAdmin()) {
            authorities.add(new SimpleGrantedAuthority(PLATFORM_ADMIN));
        }
        return List.copyOf(authorities);
    }
}
