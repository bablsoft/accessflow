package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.lifecycle.api.InvalidRetentionPolicyException;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

// Higher precedence than the security module's GlobalExceptionHandler catch-all.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class LifecycleExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(RetentionPolicyNotFoundException.class)
    ProblemDetail handleNotFound(RetentionPolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.retention_policy_not_found"),
                "RETENTION_POLICY_NOT_FOUND");
    }

    @ExceptionHandler(InvalidRetentionPolicyException.class)
    ProblemDetail handleInvalid(InvalidRetentionPolicyException ex) {
        String key = switch (ex.reason()) {
            case NO_TARGET -> "error.retention_policy_no_target";
            case TRANSFORM_REQUIRED -> "error.retention_policy_transform_required";
            case TRANSFORM_NOT_ALLOWED -> "error.retention_policy_transform_not_allowed";
            case INVALID_WINDOW -> "error.retention_policy_invalid_window";
        };
        var pd = problem(HttpStatus.BAD_REQUEST, msg(key), "INVALID_RETENTION_POLICY");
        pd.setProperty("reason", ex.reason().name());
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
