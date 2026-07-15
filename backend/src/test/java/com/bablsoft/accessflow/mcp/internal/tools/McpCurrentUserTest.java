package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCurrentUserTest {

    private final McpCurrentUser currentUser = new McpCurrentUser();

    @BeforeEach
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireClaims_throws_when_unauthenticated() {
        assertThatThrownBy(currentUser::requireClaims)
                .isInstanceOf(McpAuthenticationException.class);
    }

    @Test
    void requireClaims_throws_when_principal_is_not_jwt_claims() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "credentials"));
        assertThatThrownBy(currentUser::requireClaims)
                .isInstanceOf(McpAuthenticationException.class);
    }

    @Test
    void exposes_userId_orgId_permissions_isAdmin() {
        var userId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(authentication(
                JwtClaims.forSystemRole(userId, "u@e.c", UserRoleType.ADMIN, orgId)));

        assertThat(currentUser.userId()).isEqualTo(userId);
        assertThat(currentUser.organizationId()).isEqualTo(orgId);
        assertThat(currentUser.has(Permission.QUERY_ADMIN)).isTrue();
        assertThat(currentUser.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_false_for_other_roles() {
        SecurityContextHolder.getContext().setAuthentication(authentication(
                JwtClaims.forSystemRole(UUID.randomUUID(), "u@e.c", UserRoleType.ANALYST, UUID.randomUUID())));
        assertThat(currentUser.has(Permission.QUERY_ADMIN)).isFalse();
        assertThat(currentUser.isAdmin()).isFalse();
    }

    private static TestingAuthenticationToken authentication(JwtClaims claims) {
        return new TestingAuthenticationToken(claims, null, "ROLE_" + claims.role().name());
    }
}
