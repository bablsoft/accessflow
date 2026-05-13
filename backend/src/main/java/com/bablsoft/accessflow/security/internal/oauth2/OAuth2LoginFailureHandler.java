package com.bablsoft.accessflow.security.internal.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Maps Spring Security OAuth2 login failures to a redirect to the frontend callback URL with
 * {@code ?error=...}. Avoids surfacing raw provider error codes (they often contain client_ids
 * or detailed errors that aren't meant for end users); instead emits a single stable code that
 * the frontend translates via i18n.
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);

    private final OAuth2RedirectProperties properties;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("OAuth2 login failed: {}", exception.getMessage());
        var uri = UriComponentsBuilder.fromUriString(properties.frontendCallbackUrl())
                .queryParam("error", URLEncoder.encode("OAUTH2_LOGIN_FAILED", StandardCharsets.UTF_8))
                .build(true)
                .toUriString();
        response.sendRedirect(uri);
    }
}
