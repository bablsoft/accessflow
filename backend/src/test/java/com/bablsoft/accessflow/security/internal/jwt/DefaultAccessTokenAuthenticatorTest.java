package com.bablsoft.accessflow.security.internal.jwt;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.AccessTokenAuthenticationException;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccessTokenAuthenticatorTest {

    @Mock JwtService jwtService;
    @InjectMocks DefaultAccessTokenAuthenticator authenticator;

    @Test
    void returnsClaimsWhenJwtServiceParsesSuccessfully() {
        var claims = new JwtClaims(UUID.randomUUID(), "u@example.com",
                UserRoleType.ANALYST, UUID.randomUUID());
        when(jwtService.parseAccessToken("good")).thenReturn(claims);

        assertThat(authenticator.authenticate("good")).isSameAs(claims);
    }

    @Test
    void wrapsJwtValidationExceptionAsAccessTokenAuthenticationException() {
        when(jwtService.parseAccessToken("bad"))
                .thenThrow(new JwtValidationException("expired"));

        assertThatThrownBy(() -> authenticator.authenticate("bad"))
                .isInstanceOf(AccessTokenAuthenticationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsNullToken() {
        assertThatThrownBy(() -> authenticator.authenticate(null))
                .isInstanceOf(AccessTokenAuthenticationException.class);
    }

    @Test
    void rejectsBlankToken() {
        assertThatThrownBy(() -> authenticator.authenticate("   "))
                .isInstanceOf(AccessTokenAuthenticationException.class);
    }
}
