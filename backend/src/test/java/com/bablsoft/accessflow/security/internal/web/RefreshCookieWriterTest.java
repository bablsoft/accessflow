package com.bablsoft.accessflow.security.internal.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RefreshCookieWriterTest {

    @Test
    void writesHttpOnlySecureSameSiteStrictCookie() {
        var writer = new RefreshCookieWriter();
        var response = mock(HttpServletResponse.class);

        writer.write(response, "tokenvalue", 3600);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), captor.capture());
        var cookie = captor.getValue();
        assertThat(cookie).startsWith("refresh_token=tokenvalue");
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        assertThat(cookie).contains("Path=/api/v1/auth");
        assertThat(cookie).contains("Max-Age=3600");
    }

    @Test
    void clearingCookieUsesZeroMaxAge() {
        var writer = new RefreshCookieWriter();
        var response = mock(HttpServletResponse.class);

        writer.write(response, "", 0);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), captor.capture());
        assertThat(captor.getValue()).contains("Max-Age=0");
    }
}
