package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.core.api.DatasourceConnectionTestException;
import com.partqam.accessflow.core.api.DatasourceNameAlreadyExistsException;
import com.partqam.accessflow.core.api.DatasourceNotFoundException;
import com.partqam.accessflow.core.api.DatasourcePermissionAlreadyExistsException;
import com.partqam.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.partqam.accessflow.core.api.EmailAlreadyExistsException;
import com.partqam.accessflow.core.api.IllegalDatasourcePermissionException;
import com.partqam.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.partqam.accessflow.core.api.IllegalReviewPlanException;
import com.partqam.accessflow.core.api.IllegalUserOperationException;
import com.partqam.accessflow.core.api.QueryRequestNotFoundException;
import com.partqam.accessflow.core.api.ReviewPlanInUseException;
import com.partqam.accessflow.core.api.ReviewPlanNotFoundException;
import com.partqam.accessflow.core.api.SetupAlreadyCompletedException;
import com.partqam.accessflow.core.api.UserNotFoundException;
import com.partqam.accessflow.proxy.api.DatasourceUnavailableException;
import com.partqam.accessflow.proxy.api.InvalidSqlException;
import com.partqam.accessflow.proxy.api.PoolInitializationException;
import com.partqam.accessflow.proxy.api.QueryExecutionFailedException;
import com.partqam.accessflow.proxy.api.QueryExecutionTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
@RequiredArgsConstructor
class GlobalExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                        (a, b) -> a));
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg("error.validation_failed"));
        pd.setProperty("error", "VALIDATION_ERROR");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("fields", fieldErrors);
        return pd;
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, msg("error.unauthorized"));
        pd.setProperty("error", "UNAUTHORIZED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, msg("error.forbidden"));
        pd.setProperty("error", "FORBIDDEN");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.email_already_exists"));
        pd.setProperty("error", "EMAIL_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(SetupAlreadyCompletedException.class)
    ProblemDetail handleSetupAlreadyCompleted(SetupAlreadyCompletedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.setup_already_completed"));
        pd.setProperty("error", "SETUP_ALREADY_COMPLETED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.user_not_found"));
        pd.setProperty("error", "USER_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalUserOperationException.class)
    ProblemDetail handleIllegalUserOperation(IllegalUserOperationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.illegal_user_operation"));
        pd.setProperty("error", "ILLEGAL_USER_OPERATION");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceNotFoundException.class)
    ProblemDetail handleDatasourceNotFound(DatasourceNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.datasource_not_found"));
        pd.setProperty("error", "DATASOURCE_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceNameAlreadyExistsException.class)
    ProblemDetail handleDatasourceNameAlreadyExists(DatasourceNameAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.datasource_name_already_exists"));
        pd.setProperty("error", "DATASOURCE_NAME_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourcePermissionAlreadyExistsException.class)
    ProblemDetail handleDatasourcePermissionAlreadyExists(
            DatasourcePermissionAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.datasource_permission_already_exists"));
        pd.setProperty("error", "DATASOURCE_PERMISSION_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourcePermissionNotFoundException.class)
    ProblemDetail handleDatasourcePermissionNotFound(DatasourcePermissionNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.datasource_permission_not_found"));
        pd.setProperty("error", "DATASOURCE_PERMISSION_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceConnectionTestException.class)
    ProblemDetail handleDatasourceConnectionTest(DatasourceConnectionTestException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.datasource_connection_test_failed"));
        pd.setProperty("error", "DATASOURCE_CONNECTION_TEST_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalDatasourcePermissionException.class)
    ProblemDetail handleIllegalDatasourcePermission(IllegalDatasourcePermissionException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.illegal_datasource_permission"));
        pd.setProperty("error", "ILLEGAL_DATASOURCE_PERMISSION");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ReviewPlanNotFoundException.class)
    ProblemDetail handleReviewPlanNotFound(ReviewPlanNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.review_plan_not_found"));
        pd.setProperty("error", "REVIEW_PLAN_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ReviewPlanInUseException.class)
    ProblemDetail handleReviewPlanInUse(ReviewPlanInUseException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.review_plan_in_use"));
        pd.setProperty("error", "REVIEW_PLAN_IN_USE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalReviewPlanException.class)
    ProblemDetail handleIllegalReviewPlan(IllegalReviewPlanException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.illegal_review_plan"));
        pd.setProperty("error", "ILLEGAL_REVIEW_PLAN");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(InvalidSqlException.class)
    ProblemDetail handleInvalidSql(InvalidSqlException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "INVALID_SQL");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(QueryExecutionTimeoutException.class)
    ProblemDetail handleQueryExecutionTimeout(QueryExecutionTimeoutException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                msg("error.query_execution_timeout", ex.timeout().toSeconds()));
        pd.setProperty("error", "QUERY_EXECUTION_TIMEOUT");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("timeoutSeconds", ex.timeout().toSeconds());
        return pd;
    }

    @ExceptionHandler(QueryExecutionFailedException.class)
    ProblemDetail handleQueryExecutionFailed(QueryExecutionFailedException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "QUERY_EXECUTION_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        if (ex.sqlState() != null) {
            pd.setProperty("sqlState", ex.sqlState());
        }
        pd.setProperty("vendorCode", ex.vendorCode());
        return pd;
    }

    @ExceptionHandler(DatasourceUnavailableException.class)
    ProblemDetail handleDatasourceUnavailable(DatasourceUnavailableException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "DATASOURCE_UNAVAILABLE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(PoolInitializationException.class)
    ProblemDetail handlePoolInitialization(PoolInitializationException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        pd.setProperty("error", "POOL_INITIALIZATION_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(QueryRequestNotFoundException.class)
    ProblemDetail handleQueryRequestNotFound(QueryRequestNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.query_request_not_found"));
        pd.setProperty("error", "QUERY_REQUEST_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalQueryStatusTransitionException.class)
    ProblemDetail handleIllegalQueryStatusTransition(IllegalQueryStatusTransitionException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.illegal_status_transition"));
        pd.setProperty("error", "ILLEGAL_STATUS_TRANSITION");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("actual", ex.actual().name());
        pd.setProperty("expected", ex.expected().name());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneral(Exception ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, msg("error.internal"));
        pd.setProperty("error", "INTERNAL_SERVER_ERROR");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
