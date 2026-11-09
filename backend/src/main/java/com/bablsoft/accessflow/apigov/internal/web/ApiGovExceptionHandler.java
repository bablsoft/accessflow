package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectionTestException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationTagNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingPolicyNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorQuotaExceededException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorClassificationTagException;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorMaskingPolicyException;
import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiRequestPermissionException;
import com.bablsoft.accessflow.apigov.api.ApiRequestValidationException;
import com.bablsoft.accessflow.apigov.api.ApiReplayOperationUnavailableException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffIncompatibleException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffNotAvailableException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffUnsupportedException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftFindingNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotRefreshableException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionAlreadyAcceptedException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.DuplicateApiConnectorNameException;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorVariableException;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.api.IllegalApiUsageRangeException;
import com.bablsoft.accessflow.apigov.api.ApiReviewerNotEligibleException;
import com.bablsoft.accessflow.apigov.api.SelfApprovalNotAllowedException;
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

    @ExceptionHandler(IllegalApiUsageRangeException.class)
    ProblemDetail handleUsageRangeInvalid(IllegalApiUsageRangeException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT,
                messageSource.getMessage("error.api_usage_range_invalid", new Object[] {ex.maxDays()},
                        LocaleContextHolder.getLocale()),
                "API_USAGE_RANGE_INVALID");
        pd.setProperty("maxDays", ex.maxDays());
        return pd;
    }

    @ExceptionHandler(ApiConnectorQuotaExceededException.class)
    ProblemDetail handleConnectorQuotaExceeded(ApiConnectorQuotaExceededException ex) {
        var pd = problem(HttpStatus.TOO_MANY_REQUESTS, msg("error.api_connector_quota_exceeded"),
                "API_CONNECTOR_QUOTA_EXCEEDED");
        pd.setProperty("limitKind", ex.limitKind().name());
        pd.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
        pd.setProperty("throttledUntil", ex.throttledUntil().toString());
        pd.setProperty("queuedForRetry", true);
        return pd;
    }

    @ExceptionHandler(ApiSchemaNotFoundException.class)
    ProblemDetail handleSchemaNotFound(ApiSchemaNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_schema_not_found"), "API_SCHEMA_NOT_FOUND");
    }

    @ExceptionHandler(ApiSchemaNotRefreshableException.class)
    ProblemDetail handleSchemaNotRefreshable(ApiSchemaNotRefreshableException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.api_schema_not_refreshable"),
                "API_SCHEMA_NOT_REFRESHABLE");
    }

    @ExceptionHandler(ApiSchemaRevisionNotFoundException.class)
    ProblemDetail handleSchemaRevisionNotFound(ApiSchemaRevisionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_schema_revision_not_found"),
                "API_SCHEMA_REVISION_NOT_FOUND");
    }

    @ExceptionHandler(ApiSchemaRevisionAlreadyAcceptedException.class)
    ProblemDetail handleSchemaRevisionAlreadyAccepted(ApiSchemaRevisionAlreadyAcceptedException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.api_schema_revision_already_accepted"),
                "API_SCHEMA_REVISION_ALREADY_ACCEPTED");
    }

    @ExceptionHandler(ApiSchemaDriftFindingNotFoundException.class)
    ProblemDetail handleSchemaDriftFindingNotFound(ApiSchemaDriftFindingNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_schema_drift_finding_not_found"),
                "API_SCHEMA_DRIFT_FINDING_NOT_FOUND");
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
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.api_schema_parse"),
                "API_SCHEMA_PARSE_ERROR");
        pd.setProperty("reason", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(ApiSchemaFetchException.class)
    ProblemDetail handleSchemaFetch(ApiSchemaFetchException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.api_schema_fetch"),
                "API_SCHEMA_FETCH_ERROR");
        pd.setProperty("reason", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(ApiConnectionTestException.class)
    ProblemDetail handleConnectionTest(ApiConnectionTestException ex) {
        var pd = problem(HttpStatus.BAD_GATEWAY, msg("error.api_connection_test"), "API_CONNECTION_TEST_FAILED");
        pd.setProperty("reason", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(ApiRequestNotFoundException.class)
    ProblemDetail handleRequestNotFound(ApiRequestNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_request_not_found"), "API_REQUEST_NOT_FOUND");
    }

    @ExceptionHandler(IllegalApiRequestStateException.class)
    ProblemDetail handleIllegalState(IllegalApiRequestStateException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.api_request_invalid_state"),
                "API_REQUEST_INVALID_STATE");
        if (ex.currentStatus() != null) {
            pd.setProperty("currentStatus", ex.currentStatus().name());
        }
        return pd;
    }

    @ExceptionHandler(SelfApprovalNotAllowedException.class)
    ProblemDetail handleSelfApproval(SelfApprovalNotAllowedException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.api_request_self_approval"),
                "API_REQUEST_SELF_APPROVAL");
    }

    @ExceptionHandler(ApiReviewerNotEligibleException.class)
    ProblemDetail handleReviewerNotEligible(ApiReviewerNotEligibleException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.api_reviewer_not_eligible"),
                "API_REVIEWER_NOT_ELIGIBLE");
    }

    @ExceptionHandler(ApiRequestPermissionException.class)
    ProblemDetail handlePermissionDenied(ApiRequestPermissionException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.api_request_permission_denied"),
                "API_REQUEST_PERMISSION_DENIED");
    }

    @ExceptionHandler(ApiRequestValidationException.class)
    ProblemDetail handleValidation(ApiRequestValidationException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.api_request_validation"),
                "API_REQUEST_VALIDATION_ERROR");
        pd.setProperty("reason", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(ApiReplayOperationUnavailableException.class)
    ProblemDetail handleReplayOperationUnavailable(ApiReplayOperationUnavailableException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.api_replay_operation_unavailable"),
                "API_REPLAY_OPERATION_UNAVAILABLE");
        pd.setProperty("operationId", ex.operationId());
        return pd;
    }

    @ExceptionHandler(ApiResponseDiffNotAvailableException.class)
    ProblemDetail handleResponseDiffNotAvailable(ApiResponseDiffNotAvailableException ex) {
        var pd = problem(HttpStatus.NOT_FOUND, msg("error.api_response_diff_not_available"),
                "API_RESPONSE_DIFF_NOT_AVAILABLE");
        pd.setProperty("apiRequestId", ex.apiRequestId());
        return pd;
    }

    @ExceptionHandler(ApiResponseDiffIncompatibleException.class)
    ProblemDetail handleResponseDiffIncompatible(ApiResponseDiffIncompatibleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.api_response_diff_incompatible"),
                "API_RESPONSE_DIFF_INCOMPATIBLE");
    }

    @ExceptionHandler(ApiResponseDiffUnsupportedException.class)
    ProblemDetail handleResponseDiffUnsupported(ApiResponseDiffUnsupportedException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.api_response_diff_unsupported"),
                "API_RESPONSE_DIFF_UNSUPPORTED");
        pd.setProperty("contentType", ex.contentType());
        return pd;
    }

    @ExceptionHandler(InvalidDiffAgainstException.class)
    ProblemDetail handleInvalidDiffAgainst(InvalidDiffAgainstException ex) {
        return problem(HttpStatus.BAD_REQUEST, msg("error.validation_failed"), "VALIDATION_ERROR");
    }

    @ExceptionHandler(ApiExecutionException.class)
    ProblemDetail handleExecution(ApiExecutionException ex) {
        var pd = problem(HttpStatus.BAD_GATEWAY, msg("error.api_execution_failed"), "API_EXECUTION_FAILED");
        pd.setProperty("reason", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(ApiConnectorMaskingPolicyNotFoundException.class)
    ProblemDetail handleMaskingPolicyNotFound(ApiConnectorMaskingPolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_masking_policy_not_found"),
                "API_MASKING_POLICY_NOT_FOUND");
    }

    @ExceptionHandler(IllegalApiConnectorMaskingPolicyException.class)
    ProblemDetail handleIllegalMaskingPolicy(IllegalApiConnectorMaskingPolicyException ex) {
        // Message resolved at the throw site via MessageSource — see DefaultApiConnectorMaskingAdminService.
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), "ILLEGAL_API_MASKING_POLICY");
    }

    @ExceptionHandler(ApiConnectorClassificationTagNotFoundException.class)
    ProblemDetail handleClassificationTagNotFound(ApiConnectorClassificationTagNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_classification_tag_not_found"),
                "API_CLASSIFICATION_TAG_NOT_FOUND");
    }

    @ExceptionHandler(IllegalApiConnectorClassificationTagException.class)
    ProblemDetail handleIllegalClassificationTag(IllegalApiConnectorClassificationTagException ex) {
        // Message resolved at the throw site via MessageSource — see DefaultApiConnectorClassificationService.
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), "ILLEGAL_API_CLASSIFICATION_TAG");
    }

    @ExceptionHandler(ApiConnectorVariableNotFoundException.class)
    ProblemDetail handleVariableNotFound(ApiConnectorVariableNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.api_connector_variable_not_found"),
                "API_CONNECTOR_VARIABLE_NOT_FOUND");
    }

    @ExceptionHandler(IllegalApiConnectorVariableException.class)
    ProblemDetail handleIllegalVariable(IllegalApiConnectorVariableException ex) {
        // Message resolved at the throw site via MessageSource — see
        // DefaultApiConnectorVariableAdminService. It names variables only, never values or secrets.
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), "ILLEGAL_API_CONNECTOR_VARIABLE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
