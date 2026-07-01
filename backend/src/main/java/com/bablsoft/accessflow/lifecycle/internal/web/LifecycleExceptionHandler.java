package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewerNotEligibleException;
import com.bablsoft.accessflow.lifecycle.api.ErasureSelfApprovalException;
import com.bablsoft.accessflow.lifecycle.api.InvalidErasureConfigException;
import com.bablsoft.accessflow.lifecycle.api.InvalidRetentionPolicyException;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyNotFoundException;
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
class LifecycleExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(RetentionPolicyNotFoundException.class)
    ProblemDetail handleNotFound(RetentionPolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.retention_policy_not_found"),
                "RETENTION_POLICY_NOT_FOUND");
    }

    @ExceptionHandler(InvalidRetentionPolicyException.class)
    ProblemDetail handleInvalid(InvalidRetentionPolicyException ex) {
        String key = switch (ex.reason()) {
            case NO_TARGET -> "error.retention_policy_no_target";
            case TRANSFORM_REQUIRED -> "error.retention_policy_transform_required";
            case TRANSFORM_NOT_ALLOWED -> "error.retention_policy_transform_not_allowed";
            case INVALID_WINDOW -> "error.retention_policy_invalid_window";
        };
        var pd = problem(HttpStatus.BAD_REQUEST, msg(key), "INVALID_RETENTION_POLICY");
        pd.setProperty("reason", ex.reason().name());
        return pd;
    }

    @ExceptionHandler(InvalidErasureConfigException.class)
    ProblemDetail handleInvalidErasureConfig(InvalidErasureConfigException ex) {
        String key = switch (ex.reason()) {
            case CONDITION_COLUMN_REQUIRED -> "error.erasure_config_condition_column_required";
            case CONDITION_VALUE_ARITY -> "error.erasure_config_condition_value_arity";
            case INVALID_RAW_WHERE -> "error.erasure_config_invalid_raw_where";
            case INVALID_CRON -> "error.erasure_config_invalid_cron";
            case UNSUPPORTED_DATASOURCE -> "error.erasure_config_unsupported_datasource";
            case EMPTY_REQUEST -> "error.erasure_config_empty_request";
            case TARGET_TABLE_REQUIRED -> "error.erasure_config_target_table_required";
        };
        var pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, msg(key), "INVALID_ERASURE_CONFIG");
        pd.setProperty("reason", ex.reason().name());
        return pd;
    }

    @ExceptionHandler(DeletionRequestNotFoundException.class)
    ProblemDetail handleErasureNotFound(DeletionRequestNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.deletion_request_not_found"),
                "DELETION_REQUEST_NOT_FOUND");
    }

    @ExceptionHandler(DeletionRequestInvalidStateException.class)
    ProblemDetail handleErasureInvalidState(DeletionRequestInvalidStateException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.deletion_request_invalid_state"),
                "DELETION_REQUEST_INVALID_STATE");
        pd.setProperty("currentStatus", ex.currentStatus().name());
        return pd;
    }

    @ExceptionHandler(ErasureSelfApprovalException.class)
    ProblemDetail handleErasureSelfApproval(ErasureSelfApprovalException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.deletion_request_self_approval"),
                "DELETION_REQUEST_SELF_APPROVAL");
    }

    @ExceptionHandler(ErasureReviewerNotEligibleException.class)
    ProblemDetail handleErasureReviewerNotEligible(ErasureReviewerNotEligibleException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.deletion_request_reviewer_not_eligible"),
                "DELETION_REQUEST_REVIEWER_NOT_ELIGIBLE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
