package com.bablsoft.accessflow.mcp.internal.auth;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticationTokenTest {

    @Test
    void principal_and_authorities_reflect_claims() {
        var claims = new JwtClaims(UUID.randomUUID(), "u@e.c", UserRoleType.REVIEWER, UUID.randomUUID());
        var token = new ApiKeyAuthenticationToken(claims);
        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getPrincipal()).isSameAs(claims);
        assertThat(token.getCredentials()).isNull();
        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_REVIEWER");
    }
}
