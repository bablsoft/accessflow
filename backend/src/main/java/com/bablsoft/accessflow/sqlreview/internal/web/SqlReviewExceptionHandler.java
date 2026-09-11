package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewRulesetException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetConflictException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetNotFoundException;
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

// Higher precedence than the security module's GlobalExceptionHandler so its Exception catch-all
// does not win the resolution race. IllegalSqlReviewRuleset messages are already localized at the
// throw site (validator / codec); not-found and conflict are resolved here via message keys.
// DatasourceNotFoundException (404) and InvalidSqlException (422) stay with the global handler.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class SqlReviewExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(SqlReviewRulesetNotFoundException.class)
    ProblemDetail handleNotFound(SqlReviewRulesetNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.sql_review_ruleset_not_found", null));
        pd.setProperty("error", "SQL_REVIEW_RULESET_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("rulesetId", ex.rulesetId().toString());
        return pd;
    }

    @ExceptionHandler(SqlReviewRulesetConflictException.class)
    ProblemDetail handleConflict(SqlReviewRulesetConflictException ex) {
        ProblemDetail pd;
        if (ex.environment() == null) {
            pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    msg("error.sql_review_ruleset_default_conflict", null));
            pd.setProperty("error", "SQL_REVIEW_RULESET_DEFAULT_CONFLICT");
        } else {
            pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    msg("error.sql_review_ruleset_environment_conflict", new Object[]{ex.environment().name()}));
            pd.setProperty("error", "SQL_REVIEW_RULESET_ENVIRONMENT_CONFLICT");
            pd.setProperty("environment", ex.environment().name());
        }
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalSqlReviewRulesetException.class)
    ProblemDetail handleIllegal(IllegalSqlReviewRulesetException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setProperty("error", "SQL_REVIEW_RULESET_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    private String msg(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
