package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.security.api.ApiKeyDuplicateNameException;
import com.bablsoft.accessflow.security.api.ApiKeyNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
@RequiredArgsConstructor
class ApiKeysExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(ApiKeyNotFoundException.class)
    ProblemDetail handleNotFound(ApiKeyNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.api_key.not_found"));
        pd.setProperty("error", "API_KEY_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ApiKeyDuplicateNameException.class)
    ProblemDetail handleDuplicateName(ApiKeyDuplicateNameException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.api_key.duplicate_name"));
        pd.setProperty("error", "API_KEY_DUPLICATE_NAME");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
