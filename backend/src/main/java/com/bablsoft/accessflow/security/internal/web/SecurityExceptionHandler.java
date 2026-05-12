package com.bablsoft.accessflow.security.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class SecurityExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writeProblemDetail(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                messageSource.getMessage("error.unauthorized", null, request.getLocale()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writeProblemDetail(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                messageSource.getMessage("error.forbidden", null, request.getLocale()));
    }

    private void writeProblemDetail(HttpServletResponse response, HttpStatus status,
                                    String errorCode, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setProperty("error", errorCode);
        pd.setProperty("timestamp", Instant.now().toString());
        var traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            pd.setProperty("traceId", traceId);
        }
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
