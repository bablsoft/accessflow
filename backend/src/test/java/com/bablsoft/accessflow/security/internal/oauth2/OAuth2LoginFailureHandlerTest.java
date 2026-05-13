package com.bablsoft.accessflow.security.internal.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.AuthenticationException;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OAuth2LoginFailureHandlerTest {

    @Test
    void redirectsToFrontendCallbackWithErrorParam() throws Exception {
        var props = new OAuth2RedirectProperties("http://localhost:5173/auth/oauth/callback",
                Duration.ofMinutes(1));
        var handler = new OAuth2LoginFailureHandler(props);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);

        handler.onAuthenticationFailure(request, response, new TestAuthException("boom"));

        verify(response).sendRedirect(
                "http://localhost:5173/auth/oauth/callback?error=OAUTH2_LOGIN_FAILED");
    }

    private static class TestAuthException extends AuthenticationException {
        TestAuthException(String msg) {
            super(msg);
        }
    }
}
