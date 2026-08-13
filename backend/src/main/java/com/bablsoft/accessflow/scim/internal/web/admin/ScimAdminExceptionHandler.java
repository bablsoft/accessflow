package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.scim.api.ScimInvalidMappingException;
import com.bablsoft.accessflow.scim.api.ScimTokenNameConflictException;
import com.bablsoft.accessflow.scim.api.ScimTokenNotFoundException;
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

/**
 * ProblemDetail mapping for the SCIM admin endpoints (#621). Higher precedence than the security
 * module's GlobalExceptionHandler, whose Exception.class catch-all would otherwise win. The
 * /scim/v2 protocol surface has its own envelope — see {@code ScimErrorHandler}.
 */
@RestControllerAdvice(assignableTypes = {
        ScimAdminConfigController.class, ScimAdminTokenController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class ScimAdminExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(ScimTokenNotFoundException.class)
    ProblemDetail handleTokenNotFound(ScimTokenNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.scim_token_not_found"),
                "SCIM_TOKEN_NOT_FOUND");
    }

    @ExceptionHandler(ScimTokenNameConflictException.class)
    ProblemDetail handleTokenNameConflict(ScimTokenNameConflictException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.scim_token_name_conflict"),
                "SCIM_TOKEN_NAME_CONFLICT");
    }

    @ExceptionHandler(ScimInvalidMappingException.class)
    ProblemDetail handleInvalidMapping(ScimInvalidMappingException ex) {
        return problem(HttpStatus.BAD_REQUEST, msg("error.scim_invalid_mapping"),
                "SCIM_INVALID_MAPPING");
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", errorCode);
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
