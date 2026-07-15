package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticationTokenTest {

    @Test
    void principal_and_authorities_reflect_claims() {
        var claims = JwtClaims.forSystemRole(UUID.randomUUID(), "u@e.c", UserRoleType.REVIEWER, UUID.randomUUID());
        var token = new ApiKeyAuthenticationToken(claims);
        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getPrincipal()).isSameAs(claims);
        assertThat(token.getCredentials()).isNull();
        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_REVIEWER", "PERM_QUERY_REVIEW", "PERM_QUERY_VIEW_ALL");
    }

    @Test
    void implementsApiKeyAuthenticationMarkerForChannelDetection() {
        var claims = JwtClaims.forSystemRole(UUID.randomUUID(), "u@e.c", UserRoleType.ANALYST, UUID.randomUUID());
        assertThat(new ApiKeyAuthenticationToken(claims)).isInstanceOf(ApiKeyAuthentication.class);
    }
}
