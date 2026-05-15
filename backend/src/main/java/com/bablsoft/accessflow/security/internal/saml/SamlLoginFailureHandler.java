package com.bablsoft.accessflow.security.internal.saml;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Maps Spring Security SAML2 authentication failures onto short error codes the frontend
 * SamlCallbackPage knows how to render, and redirects there with {@code ?error=<code>}.
 *
 * Audit logging for failed SAML logins is intentionally minimal here: SamlSso failures often
 * arrive before any user identity is established, so we cannot record an actor or organization
 * with confidence. Successful logins are audited from {@link SamlLoginSuccessHandler}.
 */
@Component
@RequiredArgsConstructor
public class SamlLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SamlLoginFailureHandler.class);

    private final SamlRedirectProperties redirectProperties;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.info("SAML login failed: {}", exception.getMessage());
        var code = errorCode(exception);
        var encoded = URLEncoder.encode(code, StandardCharsets.UTF_8);
        var uri = UriComponentsBuilder.fromUriString(redirectProperties.frontendCallbackUrl())
                .queryParam("error", encoded)
                .build(true)
                .toUriString();
        response.sendRedirect(uri);
    }

    private static String errorCode(AuthenticationException exception) {
        if (exception instanceof Saml2AuthenticationException sae) {
            var err = sae.getSaml2Error();
            if (err != null && err.getErrorCode() != null) {
                return switch (err.getErrorCode()) {
                    case Saml2ErrorCodes.INVALID_SIGNATURE -> "SAML_SIGNATURE_INVALID";
                    case Saml2ErrorCodes.RELYING_PARTY_REGISTRATION_NOT_FOUND -> "SAML_NOT_CONFIGURED";
                    case Saml2ErrorCodes.MALFORMED_RESPONSE_DATA,
                         Saml2ErrorCodes.INVALID_ASSERTION,
                         Saml2ErrorCodes.INVALID_DESTINATION,
                         Saml2ErrorCodes.INVALID_ISSUER,
                         Saml2ErrorCodes.INVALID_IN_RESPONSE_TO,
                         Saml2ErrorCodes.SUBJECT_NOT_FOUND,
                         Saml2ErrorCodes.USERNAME_NOT_FOUND -> "SAML_ASSERTION_INVALID";
                    case Saml2ErrorCodes.DECRYPTION_ERROR -> "SAML_ASSERTION_INVALID";
                    default -> "SAML_LOGIN_FAILED";
                };
            }
        }
        return "SAML_LOGIN_FAILED";
    }
}
