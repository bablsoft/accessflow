package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
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
// catch-all would otherwise win the resolution race for these specific discovery exceptions.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class DiscoveryExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(DiscoveryScanAlreadyRunningException.class)
    ProblemDetail handleScanAlreadyRunning(DiscoveryScanAlreadyRunningException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messageSource.getMessage("error.discovery_scan_already_running", null,
                        LocaleContextHolder.getLocale()));
        pd.setProperty("error", "DISCOVERY_SCAN_ALREADY_RUNNING");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
