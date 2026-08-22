package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentEnvironmentNameException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentPipelineNameException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentFreezeWindowException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
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
}
