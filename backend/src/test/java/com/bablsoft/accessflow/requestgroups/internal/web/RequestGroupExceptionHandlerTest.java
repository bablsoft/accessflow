package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupNotFoundException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupPermissionException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupValidationException;
import com.bablsoft.accessflow.requestgroups.api.SelfApprovalNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestGroupExceptionHandlerTest {

    private RequestGroupExceptionHandler handler;

    @BeforeEach
    void setUp() {
        var messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        handler = new RequestGroupExceptionHandler(messageSource);
    }

    @Test
    void notFoundMapsTo404() {
        var pd = handler.handleNotFound(new RequestGroupNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "REQUEST_GROUP_NOT_FOUND");
    }

    @Test
    void illegalStateMapsTo409WithCurrentStatus() {
        var pd = handler.handleIllegalState(
                new IllegalRequestGroupStateException(RequestGroupStatus.EXECUTED, "bad"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("currentStatus", "EXECUTED");
    }

    @Test
    void selfApprovalMapsTo403() {
        var pd = handler.handleSelfApproval(new SelfApprovalNotAllowedException());
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("error", "REQUEST_GROUP_SELF_APPROVAL");
    }

    @Test
    void permissionMapsTo403WithReason() {
        var pd = handler.handlePermission(new RequestGroupPermissionException("denied"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("reason", "denied");
    }

    @Test
    void validationMapsTo422WithReason() {
        var pd = handler.handleValidation(new RequestGroupValidationException("empty"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties()).containsEntry("reason", "empty");
    }
}
