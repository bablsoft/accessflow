package com.bablsoft.accessflow.security.internal.saml;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SamlLoginFailureHandlerTest {

    private SamlLoginFailureHandler handler;

    @BeforeEach
    void setUp() {
        var properties = new SamlRedirectProperties("http://frontend/auth/saml/callback", Duration.ofMinutes(1));
        handler = new SamlLoginFailureHandler(properties);
    }

    @Test
    void mapsInvalidSignatureToSpecificCode() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var ex = new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_SIGNATURE, "bad"));

        handler.onAuthenticationFailure(request, response, ex);

        assertThat(lastRedirect(response)).contains("error=SAML_SIGNATURE_INVALID");
    }

    @Test
    void mapsRelyingPartyMissingToNotConfigured() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var ex = new Saml2AuthenticationException(
                new Saml2Error(Saml2ErrorCodes.RELYING_PARTY_REGISTRATION_NOT_FOUND, "missing"));

        handler.onAuthenticationFailure(request, response, ex);

        assertThat(lastRedirect(response)).contains("error=SAML_NOT_CONFIGURED");
    }

    @Test
    void mapsMalformedResponseToAssertionInvalid() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var ex = new Saml2AuthenticationException(
                new Saml2Error(Saml2ErrorCodes.MALFORMED_RESPONSE_DATA, "bad XML"));

        handler.onAuthenticationFailure(request, response, ex);

        assertThat(lastRedirect(response)).contains("error=SAML_ASSERTION_INVALID");
    }

    @Test
    void mapsUnknownSaml2ErrorCodeToGenericFailure() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var ex = new Saml2AuthenticationException(new Saml2Error("WHATEVER_ELSE", "novel"));

        handler.onAuthenticationFailure(request, response, ex);

        assertThat(lastRedirect(response)).contains("error=SAML_LOGIN_FAILED");
    }

    @Test
    void mapsNonSamlAuthenticationExceptionToGenericFailure() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var ex = new BadCredentialsException("nope");

        handler.onAuthenticationFailure(request, response, ex);

        assertThat(lastRedirect(response)).contains("error=SAML_LOGIN_FAILED");
    }

    private String lastRedirect(HttpServletResponse response) throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(captor.capture());
        return captor.getValue();
    }
}
