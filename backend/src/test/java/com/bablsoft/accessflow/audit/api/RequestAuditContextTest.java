package com.bablsoft.accessflow.audit.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestAuditContextTest {

    @Test
    void nullRequestReturnsNullPair() {
        var ctx = RequestAuditContext.from(null);
        assertThat(ctx.ipAddress()).isNull();
        assertThat(ctx.userAgent()).isNull();
    }

    @Test
    void usesRemoteAddrWhenNoForwardedHeader() {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("User-Agent")).thenReturn("ua/1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        var ctx = RequestAuditContext.from(request);

        assertThat(ctx.ipAddress()).isEqualTo("10.0.0.5");
        assertThat(ctx.userAgent()).isEqualTo("ua/1");
    }

    @Test
    void prefersFirstHopFromForwardedHeader() {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn(null);

        var ctx = RequestAuditContext.from(request);

        assertThat(ctx.ipAddress()).isEqualTo("203.0.113.5");
        assertThat(ctx.userAgent()).isNull();
    }

    @Test
    void singleHopForwardedIsTrimmed() {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("  203.0.113.5  ");

        var ctx = RequestAuditContext.from(request);

        assertThat(ctx.ipAddress()).isEqualTo("203.0.113.5");
    }

    @Test
    void blankForwardedFallsBackToRemoteAddr() {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        var ctx = RequestAuditContext.from(request);

        assertThat(ctx.ipAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void emptyFirstHopFallsBackToRemoteAddr() {
        // A leading comma means the first comma-split entry is empty after trim.
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(", 10.0.0.1");
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");

        var ctx = RequestAuditContext.from(request);

        assertThat(ctx.ipAddress()).isEqualTo("198.51.100.7");
    }
}
