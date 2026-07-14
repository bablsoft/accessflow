package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessGrantAlreadyExistsException;
import com.bablsoft.accessflow.access.api.AccessRequestNotCancellableException;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.api.AccessRequestNotPendingException;
import com.bablsoft.accessflow.access.api.AccessResourceKind;
import com.bablsoft.accessflow.access.api.AccessReviewerNotEligibleException;
import com.bablsoft.accessflow.access.api.InvalidAccessDurationException;
import com.bablsoft.accessflow.access.api.InvalidAccessOperationsException;
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
// catch-all would otherwise win the resolution race for these specific access exceptions.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class AccessRequestExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(AccessRequestNotFoundException.class)
    ProblemDetail handleNotFound(AccessRequestNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.access_request_not_found"),
                "ACCESS_REQUEST_NOT_FOUND");
    }

    @ExceptionHandler(AccessRequestNotPendingException.class)
    ProblemDetail handleNotPending(AccessRequestNotPendingException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.access_request_not_pending"),
                "ACCESS_REQUEST_NOT_PENDING");
        pd.setProperty("currentStatus", ex.currentStatus().name());
        return pd;
    }

    @ExceptionHandler(AccessRequestNotCancellableException.class)
    ProblemDetail handleNotCancellable(AccessRequestNotCancellableException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.access_request_not_cancellable"),
                "ACCESS_REQUEST_NOT_CANCELLABLE");
        pd.setProperty("currentStatus", ex.currentStatus().name());
        return pd;
    }

    @ExceptionHandler(AccessReviewerNotEligibleException.class)
    ProblemDetail handleNotEligible(AccessReviewerNotEligibleException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.access_reviewer_not_eligible"),
                "ACCESS_REVIEWER_NOT_ELIGIBLE");
    }

    @ExceptionHandler(AccessGrantAlreadyExistsException.class)
    ProblemDetail handleGrantExists(AccessGrantAlreadyExistsException ex) {
        var key = ex.resourceKind() == AccessResourceKind.API_CONNECTOR
                ? "error.access_grant_already_exists_connector"
                : "error.access_grant_already_exists";
        return problem(HttpStatus.CONFLICT, msg(key), "ACCESS_GRANT_ALREADY_EXISTS");
    }

    @ExceptionHandler(InvalidAccessOperationsException.class)
    ProblemDetail handleInvalidOperations(InvalidAccessOperationsException ex) {
        // The detail is already locale-resolved at the throw site via MessageSource.
        return problem(HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage() != null ? ex.getMessage() : msg("error.access_operation_unknown"),
                "INVALID_ACCESS_OPERATIONS");
    }

    @ExceptionHandler(InvalidAccessDurationException.class)
    ProblemDetail handleInvalidDuration(InvalidAccessDurationException ex) {
        // The detail is already locale-resolved at the throw site via MessageSource.
        return problem(HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage() != null ? ex.getMessage() : msg("error.access_duration_invalid"),
                "INVALID_ACCESS_DURATION");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
