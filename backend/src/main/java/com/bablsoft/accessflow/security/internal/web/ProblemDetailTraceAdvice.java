package com.bablsoft.accessflow.security.internal.web;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
class ProblemDetailTraceAdvice implements ResponseBodyAdvice<ProblemDetail> {

    static final String TRACE_ID_PROPERTY = "traceId";
    static final String MDC_TRACE_ID_KEY = "traceId";

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return ProblemDetail.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public ProblemDetail beforeBodyWrite(ProblemDetail body,
                                         MethodParameter returnType,
                                         MediaType selectedContentType,
                                         Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                         ServerHttpRequest request,
                                         ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        var traceId = MDC.get(MDC_TRACE_ID_KEY);
        if (traceId != null && !traceId.isBlank()) {
            body.setProperty(TRACE_ID_PROPERTY, traceId);
        }
        return body;
    }
}
