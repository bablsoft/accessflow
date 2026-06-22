package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.BreakGlassAlreadyReviewedException;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventNotFoundException;
import com.bablsoft.accessflow.workflow.api.BreakGlassNotPermittedException;
import com.bablsoft.accessflow.workflow.api.SelfAcknowledgeNotAllowedException;
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

// Higher precedence than the security module's GlobalExceptionHandler so these specific break-glass
// exceptions resolve to the intended status codes (AF-385).
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class BreakGlassExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(BreakGlassNotPermittedException.class)
    ProblemDetail handleNotPermitted(BreakGlassNotPermittedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                msg("error.break_glass_not_permitted"));
        pd.setProperty("error", "BREAK_GLASS_NOT_PERMITTED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(BreakGlassEventNotFoundException.class)
    ProblemDetail handleNotFound(BreakGlassEventNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.break_glass_event_not_found"));
        pd.setProperty("error", "BREAK_GLASS_EVENT_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(BreakGlassAlreadyReviewedException.class)
    ProblemDetail handleAlreadyReviewed(BreakGlassAlreadyReviewedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.break_glass_already_reviewed"));
        pd.setProperty("error", "BREAK_GLASS_ALREADY_REVIEWED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(SelfAcknowledgeNotAllowedException.class)
    ProblemDetail handleSelfAcknowledge(SelfAcknowledgeNotAllowedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                msg("error.break_glass_self_acknowledge"));
        pd.setProperty("error", "SELF_ACKNOWLEDGE_NOT_ALLOWED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
