package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.core.api.DatasourceConnectionTestException;
import com.partqam.accessflow.core.api.DatasourceNameAlreadyExistsException;
import com.partqam.accessflow.core.api.DatasourceNotFoundException;
import com.partqam.accessflow.core.api.DatasourcePermissionAlreadyExistsException;
import com.partqam.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.partqam.accessflow.core.api.EmailAlreadyExistsException;
import com.partqam.accessflow.core.api.IllegalDatasourcePermissionException;
import com.partqam.accessflow.core.api.IllegalUserOperationException;
import com.partqam.accessflow.core.api.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                        (a, b) -> a));
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("error", "VALIDATION_ERROR");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("fields", fieldErrors);
        return pd;
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setProperty("error", "UNAUTHORIZED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        pd.setProperty("error", "FORBIDDEN");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setProperty("error", "EMAIL_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setProperty("error", "USER_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalUserOperationException.class)
    ProblemDetail handleIllegalUserOperation(IllegalUserOperationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "ILLEGAL_USER_OPERATION");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceNotFoundException.class)
    ProblemDetail handleDatasourceNotFound(DatasourceNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setProperty("error", "DATASOURCE_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceNameAlreadyExistsException.class)
    ProblemDetail handleDatasourceNameAlreadyExists(DatasourceNameAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setProperty("error", "DATASOURCE_NAME_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourcePermissionAlreadyExistsException.class)
    ProblemDetail handleDatasourcePermissionAlreadyExists(
            DatasourcePermissionAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setProperty("error", "DATASOURCE_PERMISSION_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourcePermissionNotFoundException.class)
    ProblemDetail handleDatasourcePermissionNotFound(DatasourcePermissionNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setProperty("error", "DATASOURCE_PERMISSION_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceConnectionTestException.class)
    ProblemDetail handleDatasourceConnectionTest(DatasourceConnectionTestException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "DATASOURCE_CONNECTION_TEST_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalDatasourcePermissionException.class)
    ProblemDetail handleIllegalDatasourcePermission(IllegalDatasourcePermissionException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "ILLEGAL_DATASOURCE_PERMISSION");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneral(Exception ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
        pd.setProperty("error", "INTERNAL_SERVER_ERROR");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
