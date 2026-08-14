package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScimAuthenticationTokenTest {

    @Test
    void carriesPrincipalAndOnlyTheScimAuthority() {
        var principal = new ScimPrincipal(UUID.randomUUID(), UUID.randomUUID(), "okta");
        var token = new ScimAuthenticationToken(principal);

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getPrincipal()).isEqualTo(principal);
        assertThat(token.getCredentials()).isNull();
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCIM");
    }
}
