package com.bablsoft.accessflow.workflow.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPolicyExceptionsTest {

    @Test
    void notFoundCarriesPolicyId() {
        var id = UUID.randomUUID();
        var ex = new RoutingPolicyNotFoundException(id);
        assertThat(ex.policyId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void priorityConflictCarriesPriority() {
        var ex = new RoutingPolicyPriorityConflictException(7);
        assertThat(ex.priority()).isEqualTo(7);
        assertThat(ex.getMessage()).contains("7");
    }

    @Test
    void illegalRoutingPolicyKeepsMessage() {
        var ex = new IllegalRoutingPolicyException("bad");
        assertThat(ex.getMessage()).isEqualTo("bad");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void illegalRoutingPolicyKeepsCause() {
        var cause = new RuntimeException("root");
        var ex = new IllegalRoutingPolicyException("bad", cause);
        assertThat(ex.getMessage()).isEqualTo("bad");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
