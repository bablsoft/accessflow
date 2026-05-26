package com.bablsoft.accessflow.workflow.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPropertiesTest {

    @Test
    void usesProvidedTimeoutPollInterval() {
        var props = new WorkflowProperties(Duration.ofMinutes(2), Duration.ofSeconds(45));
        assertThat(props.timeoutPollInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(props.scheduledRunPollInterval()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void defaultsBothIntervalsWhenNullProvided() {
        var props = new WorkflowProperties(null, null);
        assertThat(props.timeoutPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.scheduledRunPollInterval()).isEqualTo(Duration.ofMinutes(1));
    }
}
