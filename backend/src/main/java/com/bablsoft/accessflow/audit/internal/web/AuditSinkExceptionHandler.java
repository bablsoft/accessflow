package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.AuditSinkConfigException;
import com.bablsoft.accessflow.audit.api.AuditSinkNameConflictException;
import com.bablsoft.accessflow.audit.api.AuditSinkNotFoundException;
import com.bablsoft.accessflow.audit.api.AuditSinkTestFailedException;
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
// catch-all would otherwise win the resolution race for these specific audit-sink exceptions.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class AuditSinkExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(AuditSinkNotFoundException.class)
    ProblemDetail handleNotFound(AuditSinkNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.audit_sink_not_found"));
        pd.setProperty("error", "AUDIT_SINK_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AuditSinkNameConflictException.class)
    ProblemDetail handleNameConflict(AuditSinkNameConflictException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.audit_sink_name_exists"));
        pd.setProperty("error", "AUDIT_SINK_NAME_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AuditSinkConfigException.class)
    ProblemDetail handleConfig(AuditSinkConfigException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.audit_sink_config_invalid"));
        pd.setProperty("error", "AUDIT_SINK_CONFIG_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AuditSinkTestFailedException.class)
    ProblemDetail handleTestFailed(AuditSinkTestFailedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                msg("error.audit_sink_test_failed"));
        pd.setProperty("error", "AUDIT_SINK_TEST_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
