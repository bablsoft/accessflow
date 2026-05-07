package com.partqam.accessflow.security.internal.jwt;

import com.partqam.accessflow.security.api.AccessTokenAuthenticationException;
import com.partqam.accessflow.security.api.AccessTokenAuthenticator;
import com.partqam.accessflow.security.api.JwtClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DefaultAccessTokenAuthenticator implements AccessTokenAuthenticator {

    private final JwtService jwtService;

    @Override
    public JwtClaims authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new AccessTokenAuthenticationException("Access token is missing");
        }
        try {
            return jwtService.parseAccessToken(token);
        } catch (JwtValidationException ex) {
            throw new AccessTokenAuthenticationException(ex.getMessage(), ex);
        }
    }
}
