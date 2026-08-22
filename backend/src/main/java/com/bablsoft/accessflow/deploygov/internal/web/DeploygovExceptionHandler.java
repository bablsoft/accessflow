package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentEnvironmentNameException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentPipelineNameException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentFreezeWindowException;
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

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
