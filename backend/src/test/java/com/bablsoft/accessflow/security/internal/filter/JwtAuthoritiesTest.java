package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthoritiesTest {

    @Test
    void systemRoleGetsPermAuthoritiesPlusLegacyRole() {
        var claims = JwtClaims.forSystemRole(UUID.randomUUID(), "a@b.c", UserRoleType.ANALYST,
                UUID.randomUUID(), false);

        var authorities = JwtAuthorities.from(claims).stream().map(Object::toString).toList();

        assertThat(authorities).containsExactlyInAnyOrder(
                "PERM_QUERY_SUBMIT_SELECT", "PERM_QUERY_SUBMIT_DML", "ROLE_ANALYST");
    }

    @Test
    void platformAdminAdditionallyGetsPlatformAdminAuthority() {
        var claims = JwtClaims.forSystemRole(UUID.randomUUID(), "a@b.c", UserRoleType.ADMIN,
                UUID.randomUUID(), true);

        var authorities = JwtAuthorities.from(claims).stream().map(Object::toString).toList();

        assertThat(authorities).contains("ROLE_ADMIN", "PLATFORM_ADMIN", "PERM_ROLE_MANAGE");
        assertThat(authorities).hasSize(SystemRolePermissions.of(UserRoleType.ADMIN).size() + 2);
    }

    @Test
    void customRolePrincipalGetsOnlyPermAuthorities() {
        var claims = new JwtClaims(UUID.randomUUID(), "a@b.c", null, UUID.randomUUID(),
                "Data Steward", Set.of(Permission.QUERY_REVIEW), UUID.randomUUID(), false);

        var authorities = JwtAuthorities.from(claims).stream().map(Object::toString).toList();

        assertThat(authorities).containsExactly("PERM_QUERY_REVIEW");
    }

    @Test
    void nullPermissionSetYieldsNoPermAuthorities() {
        var claims = new JwtClaims(UUID.randomUUID(), "a@b.c", UserRoleType.READONLY, null,
                "READONLY", null, UUID.randomUUID(), false);

        var authorities = JwtAuthorities.from(claims).stream().map(Object::toString).toList();

        assertThat(authorities).containsExactly("ROLE_READONLY");
    }
}
