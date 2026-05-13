package com.bablsoft.accessflow.security.internal.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Sets the HttpOnly refresh-token cookie used by both the password login flow and the OAuth2
 * exchange endpoint. Centralised so the cookie attributes stay consistent.
 */
@Component
public class RefreshCookieWriter {

    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    public static final int REFRESH_COOKIE_MAX_AGE = 7 * 24 * 3600;

    public void write(HttpServletResponse response, String value, int maxAge) {
        var cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(maxAge)
                .path("/api/v1/auth")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
