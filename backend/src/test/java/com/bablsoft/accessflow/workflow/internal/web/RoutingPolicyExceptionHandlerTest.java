package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.IllegalRoutingPolicyException;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyNotFoundException;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyPriorityConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPolicyExceptionHandlerTest {

    private final RoutingPolicyExceptionHandler handler =
            new RoutingPolicyExceptionHandler(messageSource());

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    @Test
    void handleNotFoundMapsTo404WithPolicyId() {
        var id = UUID.randomUUID();
        var pd = handler.handleNotFound(new RoutingPolicyNotFoundException(id));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "ROUTING_POLICY_NOT_FOUND");
        assertThat(pd.getProperties()).containsEntry("policyId", id.toString());
        assertThat(pd.getProperties()).containsKey("timestamp");
        assertThat(pd.getDetail()).isEqualTo("error.routing_policy_not_found");
    }

    @Test
    void handlePriorityConflictMapsTo409WithPriority() {
        var pd = handler.handlePriorityConflict(new RoutingPolicyPriorityConflictException(3));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "ROUTING_POLICY_PRIORITY_CONFLICT");
        assertThat(pd.getProperties()).containsEntry("priority", 3);
        assertThat(pd.getDetail()).isEqualTo("error.routing_policy_priority_conflict");
    }

    @Test
    void handleIllegalMapsTo422WithMessage() {
        var pd = handler.handleIllegal(new IllegalRoutingPolicyException("condition malformed"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties()).containsEntry("error", "ROUTING_POLICY_INVALID");
        assertThat(pd.getDetail()).isEqualTo("condition malformed");
    }
}
