package com.bablsoft.accessflow.scheduling.internal.web;

import com.bablsoft.accessflow.scheduling.api.JobNotFoundException;
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
class JobMonitoringExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail handleJobNotFound(JobNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messageSource.getMessage("error.job_not_found", new Object[] {ex.jobName()},
                        LocaleContextHolder.getLocale()));
        pd.setProperty("error", "JOB_NOT_FOUND");
        pd.setProperty("jobName", ex.jobName());
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
