package com.partqam.accessflow.workflow.internal.web;

import com.partqam.accessflow.workflow.api.QueryNotPendingReviewException;
import com.partqam.accessflow.workflow.api.ReviewerNotEligibleException;
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

// Higher precedence than the security module's GlobalExceptionHandler, whose Exception.class
// catch-all would otherwise win the resolution race for these specific workflow exceptions.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class ReviewExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(QueryNotPendingReviewException.class)
    ProblemDetail handleQueryNotPendingReview(QueryNotPendingReviewException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.query_not_pending_review"));
        pd.setProperty("error", "QUERY_NOT_PENDING_REVIEW");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ReviewerNotEligibleException.class)
    ProblemDetail handleReviewerNotEligible(ReviewerNotEligibleException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, msg("error.reviewer_not_eligible"));
        pd.setProperty("error", "REVIEWER_NOT_ELIGIBLE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
