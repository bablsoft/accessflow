package com.bablsoft.accessflow.security.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityExceptionHandlerTest {

    @Mock MessageSource messageSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SecurityExceptionHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        handler = new SecurityExceptionHandler(objectMapper, messageSource);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void commenceWritesUnauthorizedProblemDetail() throws Exception {
        var sink = new ServletOutputStreamSink();
        var response = stubbedResponse(sink);

        handler.commence(stubbedRequest(), response,
                new AuthenticationException("bad creds") { });

        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(response).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var root = objectMapper.readTree(new String(sink.toByteArray()));
        assertThat(root.get("status").asInt()).isEqualTo(401);
        assertThat(root.get("detail").asText()).isEqualTo("error.unauthorized");
        var props = root.get("properties");
        assertThat(props.get("error").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(hasField(props, "traceId")).isFalse();
    }

    @Test
    void handleWritesForbiddenProblemDetail() throws Exception {
        var sink = new ServletOutputStreamSink();
        var response = stubbedResponse(sink);

        handler.handle(stubbedRequest(), response, new AccessDeniedException("nope"));

        verify(response).setStatus(HttpStatus.FORBIDDEN.value());
        var root = objectMapper.readTree(new String(sink.toByteArray()));
        assertThat(root.get("status").asInt()).isEqualTo(403);
        var props = root.get("properties");
        assertThat(props.get("error").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void includesTraceIdFromMdcOnUnauthorizedResponse() throws Exception {
        MDC.put("traceId", "trace-abc-123");
        var sink = new ServletOutputStreamSink();

        handler.commence(stubbedRequest(), stubbedResponse(sink),
                new AuthenticationException("bad creds") { });

        var props = objectMapper.readTree(new String(sink.toByteArray())).get("properties");
        assertThat(props.get("traceId").asText()).isEqualTo("trace-abc-123");
    }

    @Test
    void includesTraceIdFromMdcOnForbiddenResponse() throws Exception {
        MDC.put("traceId", "trace-xyz-789");
        var sink = new ServletOutputStreamSink();

        handler.handle(stubbedRequest(), stubbedResponse(sink),
                new AccessDeniedException("nope"));

        var props = objectMapper.readTree(new String(sink.toByteArray())).get("properties");
        assertThat(props.get("traceId").asText()).isEqualTo("trace-xyz-789");
    }

    @Test
    void omitsTraceIdWhenMdcValueIsBlank() throws Exception {
        MDC.put("traceId", "  ");
        var sink = new ServletOutputStreamSink();

        handler.commence(stubbedRequest(), stubbedResponse(sink),
                new AuthenticationException("bad creds") { });

        var props = objectMapper.readTree(new String(sink.toByteArray())).get("properties");
        assertThat(hasField(props, "traceId")).isFalse();
    }

    private HttpServletRequest stubbedRequest() {
        var request = mock(HttpServletRequest.class);
        when(request.getLocale()).thenReturn(Locale.ENGLISH);
        return request;
    }

    private HttpServletResponse stubbedResponse(ServletOutputStreamSink sink) throws java.io.IOException {
        var response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(sink);
        return response;
    }

    private static boolean hasField(JsonNode node, String field) {
        return node != null && node.has(field) && !node.get(field).isNull();
    }

    private static final class ServletOutputStreamSink extends jakarta.servlet.ServletOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(jakarta.servlet.WriteListener writeListener) {
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }
}
