package com.bablsoft.accessflow.dashboard.internal.web;

import com.bablsoft.accessflow.dashboard.api.InvalidSuggestionIdException;
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

// Higher precedence than the security module's GlobalExceptionHandler catch-all (AF-498).
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class DashboardExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(InvalidSuggestionIdException.class)
    ProblemDetail handleInvalidSuggestionId(InvalidSuggestionIdException ex) {
        var detail = messageSource.getMessage("error.dashboard_suggestion_not_found", null,
                LocaleContextHolder.getLocale());
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        pd.setProperty("error", "DASHBOARD_SUGGESTION_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
