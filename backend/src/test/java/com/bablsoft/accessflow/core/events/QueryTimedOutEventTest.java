package com.bablsoft.accessflow.core.events;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTimedOutEventTest {

    @Test
    void exposesQueryRequestIdAndTimeoutHours() {
        var queryId = UUID.randomUUID();
        var event = new QueryTimedOutEvent(queryId, 24);

        assertThat(event.queryRequestId()).isEqualTo(queryId);
        assertThat(event.approvalTimeoutHours()).isEqualTo(24);
    }
}
