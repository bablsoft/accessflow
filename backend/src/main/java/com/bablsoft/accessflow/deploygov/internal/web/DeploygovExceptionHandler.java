package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentBreakGlassNotAllowedException;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateQueryInvalidException;
import com.bablsoft.accessflow.deploygov.api.DeploymentNotReleasableException;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcomeConflictException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewerNotEligibleException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewSelfAcknowledgeException;
import com.bablsoft.accessflow.deploygov.api.DeploymentSelfApprovalException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyPriorityConflictException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentEnvironmentNameException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentPipelineNameException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentFreezeWindowException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRoutingPolicyException;
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
class DeploygovExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(DeploymentPipelineNotFoundException.class)
    ProblemDetail handlePipelineNotFound(DeploymentPipelineNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.deployment_pipeline_not_found"),
                "DEPLOYMENT_PIPELINE_NOT_FOUND");
    }

    @ExceptionHandler(DuplicateDeploymentPipelineNameException.class)
    ProblemDetail handleDuplicatePipelineName(DuplicateDeploymentPipelineNameException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.deployment_pipeline_duplicate_name"),
                "DEPLOYMENT_PIPELINE_DUPLICATE_NAME");
    }

    @ExceptionHandler(DeploymentEnvironmentNotFoundException.class)
    ProblemDetail handleEnvironmentNotFound(DeploymentEnvironmentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.deployment_environment_not_found"),
                "DEPLOYMENT_ENVIRONMENT_NOT_FOUND");
    }

    @ExceptionHandler(DuplicateDeploymentEnvironmentNameException.class)
    ProblemDetail handleDuplicateEnvironmentName(DuplicateDeploymentEnvironmentNameException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.deployment_environment_duplicate_name"),
                "DEPLOYMENT_ENVIRONMENT_DUPLICATE_NAME");
    }

    @ExceptionHandler(DeploymentPermissionNotFoundException.class)
    ProblemDetail handlePermissionNotFound(DeploymentPermissionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.deployment_permission_not_found"),
                "DEPLOYMENT_PERMISSION_NOT_FOUND");
    }

    @ExceptionHandler(DeploymentFreezeWindowNotFoundException.class)
    ProblemDetail handleFreezeWindowNotFound(DeploymentFreezeWindowNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.deployment_freeze_window_not_found"),
                "DEPLOYMENT_FREEZE_WINDOW_NOT_FOUND");
    }

    @ExceptionHandler(IllegalDeploymentFreezeWindowException.class)
    ProblemDetail handleIllegalFreezeWindow(IllegalDeploymentFreezeWindowException ex) {
        // Message resolved at the throw site via MessageSource — see
        // DefaultDeploymentFreezeWindowService.
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "DEPLOYMENT_FREEZE_WINDOW_INVALID");
    }

    @ExceptionHandler(DeploymentRequestNotFoundException.class)
    ProblemDetail handleRequestNotFound(DeploymentRequestNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.deployment_request_not_found"),
                "DEPLOYMENT_REQUEST_NOT_FOUND");
    }

    @ExceptionHandler(IllegalDeploymentRequestStateException.class)
    ProblemDetail handleIllegalRequestState(IllegalDeploymentRequestStateException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.deployment_request_invalid_state"),
                "DEPLOYMENT_REQUEST_INVALID_STATE");
        if (ex.getCurrentStatus() != null) {
            pd.setProperty("currentStatus", ex.getCurrentStatus().name());
        }
        return pd;
    }

    @ExceptionHandler(DeploymentRequestPermissionException.class)
    ProblemDetail handleRequestPermission(DeploymentRequestPermissionException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.deployment_request_permission_denied"),
                "DEPLOYMENT_REQUEST_PERMISSION_DENIED");
    }

    // 409, not apigov's 403: the conflict is with the resource's provenance (its submitter), not
    // the caller's permissions — see docs/04-api-spec.md § Deployment reviews.
    @ExceptionHandler(DeploymentSelfApprovalException.class)
    ProblemDetail handleSelfApproval(DeploymentSelfApprovalException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.deployment_request_self_approval"),
                "DEPLOYMENT_REQUEST_SELF_APPROVAL");
    }

    @ExceptionHandler(DeploymentReviewerNotEligibleException.class)
    ProblemDetail handleReviewerNotEligible(DeploymentReviewerNotEligibleException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.deployment_reviewer_not_eligible"),
                "DEPLOYMENT_REVIEWER_NOT_ELIGIBLE");
    }

    @ExceptionHandler(DeploymentBreakGlassNotAllowedException.class)
    ProblemDetail handleBreakGlassNotAllowed(DeploymentBreakGlassNotAllowedException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.deployment_break_glass_not_allowed"),
                "DEPLOYMENT_BREAK_GLASS_NOT_ALLOWED");
    }

    @ExceptionHandler(DeploymentRoutingPolicyNotFoundException.class)
    ProblemDetail handleRoutingPolicyNotFound(DeploymentRoutingPolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.deployment_routing_policy_not_found"),
                "DEPLOYMENT_ROUTING_POLICY_NOT_FOUND");
    }

    @ExceptionHandler(DeploymentRoutingPolicyPriorityConflictException.class)
    ProblemDetail handleRoutingPolicyPriority(DeploymentRoutingPolicyPriorityConflictException ex) {
        return problem(HttpStatus.CONFLICT,
                msg("error.deployment_routing_policy_priority_conflict"),
                "DEPLOYMENT_ROUTING_POLICY_PRIORITY_CONFLICT");
    }

    @ExceptionHandler(IllegalDeploymentRoutingPolicyException.class)
    ProblemDetail handleIllegalRoutingPolicy(IllegalDeploymentRoutingPolicyException ex) {
        // Message resolved at the throw site via MessageSource — see
        // DefaultDeploymentRoutingPolicyService.
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "DEPLOYMENT_ROUTING_POLICY_INVALID");
    }

    @ExceptionHandler(DeploymentGateQueryInvalidException.class)
    ProblemDetail handleGateQueryInvalid(DeploymentGateQueryInvalidException ex) {
        return problem(HttpStatus.BAD_REQUEST, msg("error.deployment_gate_query_invalid"),
                "DEPLOYMENT_GATE_QUERY_INVALID");
    }

    @ExceptionHandler(DeploymentNotReleasableException.class)
    ProblemDetail handleNotReleasable(DeploymentNotReleasableException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.deployment_not_releasable"),
                "DEPLOYMENT_NOT_RELEASABLE");
        if (ex.getCurrentStatus() != null) {
            pd.setProperty("currentStatus", ex.getCurrentStatus().name());
        }
        return pd;
    }

    @ExceptionHandler(DeploymentOutcomeConflictException.class)
    ProblemDetail handleOutcomeConflict(DeploymentOutcomeConflictException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.deployment_outcome_conflict"),
                "DEPLOYMENT_OUTCOME_CONFLICT");
    }

    @ExceptionHandler(DeploymentRollbackReviewNotFoundException.class)
    ProblemDetail handleRollbackReviewNotFound(DeploymentRollbackReviewNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.deployment_rollback_review_not_found"),
                "DEPLOYMENT_ROLLBACK_REVIEW_NOT_FOUND");
    }

    // 409 like self-approval: the conflict is with the resource's provenance, not permissions.
    @ExceptionHandler(DeploymentRollbackReviewSelfAcknowledgeException.class)
    ProblemDetail handleRollbackSelfAcknowledge(
            DeploymentRollbackReviewSelfAcknowledgeException ex) {
        return problem(HttpStatus.CONFLICT,
                msg("error.deployment_rollback_review_self_acknowledge"),
                "DEPLOYMENT_ROLLBACK_REVIEW_SELF_ACKNOWLEDGE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
