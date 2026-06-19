package com.bablsoft.accessflow.workflow.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySnapshotNotFoundExceptionTest {

    @Test
    void carriesQueryRequestId() {
        var id = UUID.randomUUID();
        var ex = new QuerySnapshotNotFoundException(id);

        assertThat(ex.queryRequestId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }
}
