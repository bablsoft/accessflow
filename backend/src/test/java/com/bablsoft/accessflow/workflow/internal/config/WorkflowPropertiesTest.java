package com.bablsoft.accessflow.workflow.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPropertiesTest {

    @Test
    void usesProvidedIntervals() {
        var props = new WorkflowProperties(Duration.ofMinutes(2), Duration.ofSeconds(45),
                Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(3));
        assertThat(props.timeoutPollInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(props.scheduledRunPollInterval()).isEqualTo(Duration.ofSeconds(45));
        assertThat(props.recurringRunPollInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.recurrenceMinInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.escalationPollInterval()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void defaultsAllIntervalsWhenNullProvided() {
        var props = new WorkflowProperties(null, null, null, null, null);
        assertThat(props.timeoutPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.scheduledRunPollInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.recurringRunPollInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.recurrenceMinInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.escalationPollInterval()).isEqualTo(Duration.ofMinutes(5));
    }
}
