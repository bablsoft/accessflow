package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AnomalyNotFoundException;
import com.bablsoft.accessflow.ai.api.IllegalAnomalyStatusTransitionException;
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
class BehaviorAnomalyExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(AnomalyNotFoundException.class)
    ProblemDetail handleNotFound(AnomalyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.behavior_anomaly_not_found"),
                "BEHAVIOR_ANOMALY_NOT_FOUND");
    }

    @ExceptionHandler(IllegalAnomalyStatusTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalAnomalyStatusTransitionException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.behavior_anomaly_illegal_transition"),
                "BEHAVIOR_ANOMALY_ILLEGAL_TRANSITION");
        pd.setProperty("currentStatus", ex.from().name());
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
