package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.CollaborationNotPermittedException;
import com.bablsoft.accessflow.workflow.api.QueryCommentNotFoundException;
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

// Higher precedence than the security module's GlobalExceptionHandler so its Exception.class
// catch-all does not win the resolution race for these collaboration exceptions.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class QueryCommentExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(QueryCommentNotFoundException.class)
    ProblemDetail handleNotFound(QueryCommentNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.query_comment_not_found"));
        pd.setProperty("error", "QUERY_COMMENT_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("commentId", ex.commentId().toString());
        return pd;
    }

    @ExceptionHandler(CollaborationNotPermittedException.class)
    ProblemDetail handleNotPermitted(CollaborationNotPermittedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                msg("error.collaboration_not_permitted"));
        pd.setProperty("error", "COLLABORATION_FORBIDDEN");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("queryId", ex.queryRequestId().toString());
        return pd;
    }
}
