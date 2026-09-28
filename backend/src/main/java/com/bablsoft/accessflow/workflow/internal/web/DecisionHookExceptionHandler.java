package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.DecisionHookNotFoundException;
import com.bablsoft.accessflow.workflow.api.DecisionHookScopeConflictException;
import com.bablsoft.accessflow.workflow.api.IllegalDecisionHookException;
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

// Higher precedence than the security module's GlobalExceptionHandler, whose catch-all would
// otherwise win the resolution race for these decision-hook exceptions (#945).
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class DecisionHookExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(DecisionHookNotFoundException.class)
    ProblemDetail handleNotFound(DecisionHookNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.decision_hook_not_found"));
        pd.setProperty("error", "DECISION_HOOK_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("decisionHookId", ex.hookId().toString());
        return pd;
    }

    @ExceptionHandler(DecisionHookScopeConflictException.class)
    ProblemDetail handleConflict(DecisionHookScopeConflictException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg(ex.datasourceId() == null
                        ? "error.decision_hook_default_conflict"
                        : "error.decision_hook_datasource_conflict"));
        pd.setProperty("error", "DECISION_HOOK_SCOPE_CONFLICT");
        pd.setProperty("timestamp", Instant.now().toString());
        if (ex.datasourceId() != null) {
            pd.setProperty("datasourceId", ex.datasourceId().toString());
        }
        return pd;
    }

    @ExceptionHandler(IllegalDecisionHookException.class)
    ProblemDetail handleIllegal(IllegalDecisionHookException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                msg(ex.messageKey()));
        pd.setProperty("error", "DECISION_HOOK_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
