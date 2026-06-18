package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthoritiesTest {

    @Test
    void nonPlatformAdminGetsOnlyRole() {
        var claims = new JwtClaims(UUID.randomUUID(), "a@b.c", UserRoleType.ANALYST,
                UUID.randomUUID(), false);

        var authorities = JwtAuthorities.from(claims).stream().map(Object::toString).toList();

        assertThat(authorities).containsExactly("ROLE_ANALYST");
    }

    @Test
    void platformAdminGetsRoleAndPlatformAdminAuthority() {
        var claims = new JwtClaims(UUID.randomUUID(), "a@b.c", UserRoleType.ADMIN,
                UUID.randomUUID(), true);

        var authorities = JwtAuthorities.from(claims).stream().map(Object::toString).toList();

        assertThat(authorities).containsExactlyInAnyOrder("ROLE_ADMIN", "PLATFORM_ADMIN");
    }
}
