package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordExecutionCommandTest {

    private final UUID id = UUID.randomUUID();
    private final Instant started = Instant.parse("2026-05-07T10:00:00Z");
    private final Instant completed = Instant.parse("2026-05-07T10:00:01Z");

    @Test
    void acceptsExecutedOutcome() {
        var cmd = new RecordExecutionCommand(id, QueryStatus.EXECUTED, 5L, 100, null,
                started, completed);
        assertThat(cmd.outcome()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(cmd.rowsAffected()).isEqualTo(5L);
        assertThat(cmd.durationMs()).isEqualTo(100);
    }

    @Test
    void acceptsFailedOutcomeWithErrorMessage() {
        var cmd = new RecordExecutionCommand(id, QueryStatus.FAILED, null, 50,
                "boom", started, completed);
        assertThat(cmd.outcome()).isEqualTo(QueryStatus.FAILED);
        assertThat(cmd.errorMessage()).isEqualTo("boom");
        assertThat(cmd.rowsAffected()).isNull();
    }

    @Test
    void rejectsOutcomesOtherThanExecutedOrFailed() {
        for (var status : QueryStatus.values()) {
            if (status == QueryStatus.EXECUTED || status == QueryStatus.FAILED) {
                continue;
            }
            assertThatThrownBy(() -> new RecordExecutionCommand(id, status, null, 0, null,
                    started, completed))
                    .as("expected rejection for outcome=%s", status)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome must be EXECUTED or FAILED");
        }
    }
}
