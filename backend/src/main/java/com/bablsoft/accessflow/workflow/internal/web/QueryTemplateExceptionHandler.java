package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.QueryTemplateAccessDeniedException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNameAlreadyExistsException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNotFoundException;
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

// Higher precedence than the security module's GlobalExceptionHandler so the catch-all there
// does not win the resolution race for these specific template exceptions.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class QueryTemplateExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(QueryTemplateNotFoundException.class)
    ProblemDetail handleNotFound(QueryTemplateNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.query_template_not_found"));
        pd.setProperty("error", "QUERY_TEMPLATE_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("templateId", ex.templateId().toString());
        return pd;
    }

    @ExceptionHandler(QueryTemplateAccessDeniedException.class)
    ProblemDetail handleAccessDenied(QueryTemplateAccessDeniedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                msg("error.query_template_access_denied"));
        pd.setProperty("error", "QUERY_TEMPLATE_FORBIDDEN");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("templateId", ex.templateId().toString());
        return pd;
    }

    @ExceptionHandler(QueryTemplateNameAlreadyExistsException.class)
    ProblemDetail handleNameConflict(QueryTemplateNameAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.query_template_name_conflict"));
        pd.setProperty("error", "QUERY_TEMPLATE_NAME_CONFLICT");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("name", ex.name());
        return pd;
    }
}
