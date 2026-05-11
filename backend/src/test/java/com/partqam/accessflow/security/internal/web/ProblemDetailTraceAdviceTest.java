package com.partqam.accessflow.security.internal.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProblemDetailTraceAdviceTest {

    private final ProblemDetailTraceAdvice advice = new ProblemDetailTraceAdvice();

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writesMdcTraceIdOntoProblemDetail() {
        MDC.put("traceId", "abcdef0123456789");
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "boom");

        var result = advice.beforeBodyWrite(pd, methodParam(), MediaType.APPLICATION_PROBLEM_JSON,
                jacksonConverter(), mock(org.springframework.http.server.ServerHttpRequest.class),
                mock(org.springframework.http.server.ServerHttpResponse.class));

        assertThat(result.getProperties()).containsEntry("traceId", "abcdef0123456789");
    }

    @Test
    void leavesProblemDetailUnchangedWhenMdcEmpty() {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "boom");

        var result = advice.beforeBodyWrite(pd, methodParam(), MediaType.APPLICATION_PROBLEM_JSON,
                jacksonConverter(), mock(org.springframework.http.server.ServerHttpRequest.class),
                mock(org.springframework.http.server.ServerHttpResponse.class));

        assertThat(result.getProperties() == null || !result.getProperties().containsKey("traceId"))
                .isTrue();
    }

    @Test
    void ignoresBlankMdcTraceId() {
        MDC.put("traceId", "   ");
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "boom");

        var result = advice.beforeBodyWrite(pd, methodParam(), MediaType.APPLICATION_PROBLEM_JSON,
                jacksonConverter(), mock(org.springframework.http.server.ServerHttpRequest.class),
                mock(org.springframework.http.server.ServerHttpResponse.class));

        assertThat(result.getProperties() == null || !result.getProperties().containsKey("traceId"))
                .isTrue();
    }

    @Test
    void nullBodyReturnsNull() {
        MDC.put("traceId", "abc");

        var result = advice.beforeBodyWrite(null, methodParam(), MediaType.APPLICATION_PROBLEM_JSON,
                jacksonConverter(), mock(org.springframework.http.server.ServerHttpRequest.class),
                mock(org.springframework.http.server.ServerHttpResponse.class));

        assertThat(result).isNull();
    }

    @Test
    void supportsProblemDetailReturnType() throws Exception {
        var param = new MethodParameter(StubController.class.getDeclaredMethod("returnsProblemDetail"), -1);

        assertThat(advice.supports(param, jacksonConverter())).isTrue();
    }

    @Test
    void rejectsNonProblemDetailReturnType() throws Exception {
        var param = new MethodParameter(StubController.class.getDeclaredMethod("returnsString"), -1);

        assertThat(advice.supports(param, jacksonConverter())).isFalse();
    }

    private MethodParameter methodParam() {
        try {
            return new MethodParameter(StubController.class.getDeclaredMethod("returnsProblemDetail"), -1);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private Class<? extends HttpMessageConverter<?>> jacksonConverter() {
        return StringHttpMessageConverter.class;
    }

    @SuppressWarnings("unused")
    private static final class StubController {
        ProblemDetail returnsProblemDetail() {
            return null;
        }

        String returnsString() {
            return null;
        }
    }
}
