package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestAuditContextArgumentResolverTest {

    private RequestAuditContextArgumentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RequestAuditContextArgumentResolver();
    }

    @Test
    void supportsParameterMatchesAuditContextOnly() throws NoSuchMethodException {
        assertThat(resolver.supportsParameter(parameterFor("withContext"))).isTrue();
        assertThat(resolver.supportsParameter(parameterFor("withString"))).isFalse();
    }

    @Test
    void returnsNullPairWhenNoServletRequest() throws Exception {
        var webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(null);

        var result = resolver.resolveArgument(parameterFor("withContext"), null, webRequest, null);

        assertThat(result.ipAddress()).isNull();
        assertThat(result.userAgent()).isNull();
    }

    @Test
    void usesRemoteAddrWhenNoForwardedHeader() throws Exception {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("User-Agent")).thenReturn("ua/1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        var webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);

        var result = resolver.resolveArgument(parameterFor("withContext"), null, webRequest, null);

        assertThat(result.ipAddress()).isEqualTo("10.0.0.5");
        assertThat(result.userAgent()).isEqualTo("ua/1");
    }

    @Test
    void prefersFirstHopFromForwardedHeader() throws Exception {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn(null);
        var webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);

        var result = resolver.resolveArgument(parameterFor("withContext"), null, webRequest, null);

        assertThat(result.ipAddress()).isEqualTo("203.0.113.5");
        assertThat(result.userAgent()).isNull();
    }

    @Test
    void singleHopForwardedIsTrimmed() throws Exception {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("  203.0.113.5  ");
        var webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);

        var result = resolver.resolveArgument(parameterFor("withContext"), null, webRequest, null);

        assertThat(result.ipAddress()).isEqualTo("203.0.113.5");
    }

    @Test
    void blankForwardedFallsBackToRemoteAddr() throws Exception {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);

        var result = resolver.resolveArgument(parameterFor("withContext"), null, webRequest, null);

        assertThat(result.ipAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void emptyFirstHopFallsBackToRemoteAddr() throws Exception {
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(", 10.0.0.1");
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");
        var webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);

        var result = resolver.resolveArgument(parameterFor("withContext"), null, webRequest, null);

        assertThat(result.ipAddress()).isEqualTo("198.51.100.7");
    }

    private static MethodParameter parameterFor(String methodName) throws NoSuchMethodException {
        Method method = SampleHandler.class.getDeclaredMethod(methodName, methodName.equals("withContext")
                ? RequestAuditContext.class : String.class);
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    private static final class SampleHandler {
        void withContext(RequestAuditContext context) {}
        void withString(String value) {}
    }
}
