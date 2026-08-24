package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentBreakGlassNotAllowedException;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewerNotEligibleException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyPriorityConflictException;
import com.bablsoft.accessflow.deploygov.api.DeploymentSelfApprovalException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentEnvironmentNameException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentPipelineNameException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentFreezeWindowException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRoutingPolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploygovExceptionHandlerTest {

    private DeploygovExceptionHandler handler;

    @BeforeEach
    void setUp() {
        var messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        handler = new DeploygovExceptionHandler(messageSource);
    }

    @Test
    void pipelineNotFoundIs404() {
        var pd = handler.handlePipelineNotFound(
                new DeploymentPipelineNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_PIPELINE_NOT_FOUND");
        assertThat(pd.getDetail()).isEqualTo("error.deployment_pipeline_not_found");
        assertThat(pd.getProperties()).containsKey("timestamp");
    }

    @Test
    void duplicatePipelineNameIs409() {
        var pd = handler.handleDuplicatePipelineName(
                new DuplicateDeploymentPipelineNameException("payments-api"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_PIPELINE_DUPLICATE_NAME");
    }

    @Test
    void environmentNotFoundIs404() {
        var pd = handler.handleEnvironmentNotFound(
                new DeploymentEnvironmentNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_ENVIRONMENT_NOT_FOUND");
    }

    @Test
    void duplicateEnvironmentNameIs409() {
        var pd = handler.handleDuplicateEnvironmentName(
                new DuplicateDeploymentEnvironmentNameException("production"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties())
                .containsEntry("error", "DEPLOYMENT_ENVIRONMENT_DUPLICATE_NAME");
    }

    @Test
    void permissionNotFoundIs404() {
        var pd = handler.handlePermissionNotFound(
                new DeploymentPermissionNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_PERMISSION_NOT_FOUND");
    }

    @Test
    void freezeWindowNotFoundIs404() {
        var pd = handler.handleFreezeWindowNotFound(
                new DeploymentFreezeWindowNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_FREEZE_WINDOW_NOT_FOUND");
    }

    @Test
    void illegalFreezeWindowIs400AndPassesThrowSiteMessageThrough() {
        var pd = handler.handleIllegalFreezeWindow(
                new IllegalDeploymentFreezeWindowException("already localized"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_FREEZE_WINDOW_INVALID");
        assertThat(pd.getDetail()).isEqualTo("already localized");
    }

    @Test
    void requestNotFoundIs404() {
        var pd = handler.handleRequestNotFound(
                new DeploymentRequestNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_REQUEST_NOT_FOUND");
        assertThat(pd.getDetail()).isEqualTo("error.deployment_request_not_found");
    }

    @Test
    void illegalRequestStateIs409AndCarriesTheCurrentStatus() {
        var pd = handler.handleIllegalRequestState(new IllegalDeploymentRequestStateException(
                QueryStatus.EXECUTED, QueryStatus.CANCELLED));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_REQUEST_INVALID_STATE");
        assertThat(pd.getProperties()).containsEntry("currentStatus", "EXECUTED");
    }

    @Test
    void illegalRequestStateOmitsTheStatusWhenAbsent() {
        var pd = handler.handleIllegalRequestState(
                new IllegalDeploymentRequestStateException(null, "no status"));

        assertThat(pd.getProperties()).doesNotContainKey("currentStatus");
    }

    @Test
    void requestPermissionDeniedIs403() {
        var pd = handler.handleRequestPermission(
                new DeploymentRequestPermissionException("nope"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_REQUEST_PERMISSION_DENIED");
        assertThat(pd.getDetail()).isEqualTo("error.deployment_request_permission_denied");
    }

    @Test
    void selfApprovalIs409() {
        var pd = handler.handleSelfApproval(new DeploymentSelfApprovalException());

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_REQUEST_SELF_APPROVAL");
        assertThat(pd.getDetail()).isEqualTo("error.deployment_request_self_approval");
    }

    @Test
    void reviewerNotEligibleIs403() {
        var pd = handler.handleReviewerNotEligible(
                new DeploymentReviewerNotEligibleException(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_REVIEWER_NOT_ELIGIBLE");
        assertThat(pd.getDetail()).isEqualTo("error.deployment_reviewer_not_eligible");
    }

    @Test
    void breakGlassNotAllowedIs403() {
        var pd = handler.handleBreakGlassNotAllowed(
                new DeploymentBreakGlassNotAllowedException("no grant"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_BREAK_GLASS_NOT_ALLOWED");
        assertThat(pd.getDetail()).isEqualTo("error.deployment_break_glass_not_allowed");
    }

    @Test
    void routingPolicyNotFoundIs404() {
        var pd = handler.handleRoutingPolicyNotFound(
                new DeploymentRoutingPolicyNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_ROUTING_POLICY_NOT_FOUND");
    }

    @Test
    void routingPolicyPriorityConflictIs409() {
        var pd = handler.handleRoutingPolicyPriority(
                new DeploymentRoutingPolicyPriorityConflictException(10));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties())
                .containsEntry("error", "DEPLOYMENT_ROUTING_POLICY_PRIORITY_CONFLICT");
    }

    @Test
    void illegalRoutingPolicyIs400AndPassesThrowSiteMessageThrough() {
        var pd = handler.handleIllegalRoutingPolicy(
                new IllegalDeploymentRoutingPolicyException("already localized"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "DEPLOYMENT_ROUTING_POLICY_INVALID");
        assertThat(pd.getDetail()).isEqualTo("already localized");
    }
}
