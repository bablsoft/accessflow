package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectionTestException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.DuplicateApiConnectorNameException;
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
class ApiGovExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(ApiConnectorNotFoundException.class)
    ProblemDetail handleConnectorNotFound(ApiConnectorNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_connector_not_found"),
                "API_CONNECTOR_NOT_FOUND");
    }

    @ExceptionHandler(ApiSchemaNotFoundException.class)
    ProblemDetail handleSchemaNotFound(ApiSchemaNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_schema_not_found"), "API_SCHEMA_NOT_FOUND");
    }

    @ExceptionHandler(ApiConnectorPermissionNotFoundException.class)
    ProblemDetail handlePermissionNotFound(ApiConnectorPermissionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_permission_not_found"),
                "API_PERMISSION_NOT_FOUND");
    }

    @ExceptionHandler(DuplicateApiConnectorNameException.class)
    ProblemDetail handleDuplicateName(DuplicateApiConnectorNameException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.api_connector_duplicate_name"),
                "API_CONNECTOR_DUPLICATE_NAME");
    }

    @ExceptionHandler(ApiSchemaParseException.class)
    ProblemDetail handleSchemaParse(ApiSchemaParseException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.api_schema_parse"),
                "API_SCHEMA_PARSE_ERROR");
        pd.setProperty("reason", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(ApiConnectionTestException.class)
    ProblemDetail handleConnectionTest(ApiConnectionTestException ex) {
        var pd = problem(HttpStatus.BAD_GATEWAY, msg("error.api_connection_test"), "API_CONNECTION_TEST_FAILED");
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
