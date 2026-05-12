package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IllegalQueryStatusTransitionExceptionTest {

    @Test
    void exposesQueryIdActualAndExpectedThroughAccessors() {
        var queryId = UUID.randomUUID();

        var ex = new IllegalQueryStatusTransitionException(
                queryId, QueryStatus.APPROVED, QueryStatus.PENDING_REVIEW);

        assertThat(ex.queryRequestId()).isEqualTo(queryId);
        assertThat(ex.actual()).isEqualTo(QueryStatus.APPROVED);
        assertThat(ex.expected()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void messageIncludesIdAndBothStates() {
        var queryId = UUID.randomUUID();

        var ex = new IllegalQueryStatusTransitionException(
                queryId, QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW);

        assertThat(ex.getMessage())
                .contains(queryId.toString())
                .contains("PENDING_AI")
                .contains("PENDING_REVIEW");
    }
}
