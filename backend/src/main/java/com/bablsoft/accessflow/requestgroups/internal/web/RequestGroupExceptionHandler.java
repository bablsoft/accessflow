package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupNotFoundException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupPermissionException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupValidationException;
import com.bablsoft.accessflow.requestgroups.api.SelfApprovalNotAllowedException;
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
class RequestGroupExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(RequestGroupNotFoundException.class)
    ProblemDetail handleNotFound(RequestGroupNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.request_group_not_found"),
                "REQUEST_GROUP_NOT_FOUND");
    }

    @ExceptionHandler(IllegalRequestGroupStateException.class)
    ProblemDetail handleIllegalState(IllegalRequestGroupStateException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.request_group_invalid_state"),
                "REQUEST_GROUP_INVALID_STATE");
        if (ex.currentStatus() != null) {
            pd.setProperty("currentStatus", ex.currentStatus().name());
        }
        return pd;
    }

    @ExceptionHandler(SelfApprovalNotAllowedException.class)
    ProblemDetail handleSelfApproval(SelfApprovalNotAllowedException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.request_group_self_approval"),
                "REQUEST_GROUP_SELF_APPROVAL");
    }

    @ExceptionHandler(RequestGroupPermissionException.class)
    ProblemDetail handlePermission(RequestGroupPermissionException ex) {
        var pd = problem(HttpStatus.FORBIDDEN, msg("error.request_group_permission_denied"),
                "REQUEST_GROUP_PERMISSION_DENIED");
        pd.setProperty("reason", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(RequestGroupValidationException.class)
    ProblemDetail handleValidation(RequestGroupValidationException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.request_group_validation"),
                "REQUEST_GROUP_VALIDATION_ERROR");
        pd.setProperty("reason", ex.getMessage());
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
