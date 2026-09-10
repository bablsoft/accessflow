package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.QuerySuggestionRecomputeInProgressException;
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
// does not turn this 409 into a generic 500.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class QuerySuggestionExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(QuerySuggestionRecomputeInProgressException.class)
    ProblemDetail handleRecomputeInProgress(QuerySuggestionRecomputeInProgressException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messageSource.getMessage("error.query_suggestion_recompute_in_progress", null,
                        LocaleContextHolder.getLocale()));
        pd.setProperty("error", "QUERY_SUGGESTION_RECOMPUTE_IN_PROGRESS");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("datasourceId", ex.datasourceId().toString());
        return pd;
    }
}
